#!/usr/bin/env node
// Conector MCP de Transfer: expone la herramienta upload_file por stdio.
// Sin dependencias, requiere Node 20 o superior.
//
// Configuración en ~/.transfer-connector.json:
//   { "url": "...", "token": "...", "autostart": { "jar": "...", "workDir": "..." } }
// "autostart" es opcional y solo se aplica a URLs locales.
// La variable TRANSFER_CONNECTOR_CONFIG permite usar otra ruta.

import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { closeSync, createReadStream, mkdirSync, openSync } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import { homedir } from 'node:os';
import path from 'node:path';
import readline from 'node:readline';

const CONFIG_PATH = process.env.TRANSFER_CONNECTOR_CONFIG
  ?? path.join(homedir(), '.transfer-connector.json');
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);
const STARTUP_TIMEOUT_MS = 90_000;

const TOOL = {
  name: 'upload_file',
  description: 'Sube un fichero de este equipo al servidor Transfer del usuario. '
    + 'Una llamada por fichero. Las carpetas hay que comprimirlas antes.',
  inputSchema: {
    type: 'object',
    properties: {
      path: { type: 'string', description: 'Ruta absoluta del fichero que se va a subir' },
    },
    required: ['path'],
    additionalProperties: false,
  },
};

class UserError extends Error {}

async function loadConfig() {
  let config;
  try {
    config = JSON.parse(await readFile(CONFIG_PATH, 'utf8'));
  } catch (e) {
    throw new UserError(`No se pudo leer la configuración en ${CONFIG_PATH}: ${e.message}`);
  }
  if (!config.url || !config.token) {
    throw new UserError(`Faltan "url" o "token" en ${CONFIG_PATH}.`);
  }
  // La barra final conserva el prefijo si Transfer se sirve en una subruta
  const base = new URL(String(config.url).replace(/\/*$/, '/'));
  if (base.protocol !== 'https:' && !LOCAL_HOSTS.has(base.hostname)) {
    throw new UserError(`La URL ${base} debe usar HTTPS: el token y los ficheros viajarían sin cifrar.`);
  }
  return { base, token: config.token, autostart: config.autostart };
}

async function isUp(base) {
  try {
    await fetch(new URL('login', base), { redirect: 'manual' });
    return true;
  } catch {
    return false;
  }
}

function launchApp({ jar, workDir }, base, token) {
  mkdirSync(path.join(workDir, 'logs'), { recursive: true });
  const log = openSync(path.join(workDir, 'logs', 'transfer.log'), 'a');
  const child = spawn('java', ['-jar', jar, `--server.port=${base.port || 80}`], {
    cwd: workDir,
    env: { ...process.env, API_TOKEN: token },
    detached: true,
    windowsHide: true,
    stdio: ['ignore', log, log],
  });
  closeSync(log);
  child.unref();
  return child;
}

async function startAndWait(autostart, base, token) {
  const child = launchApp(autostart, base, token);
  let stopped = false;
  child.once('error', () => { stopped = true; });
  child.once('exit', () => { stopped = true; });

  const deadline = Date.now() + STARTUP_TIMEOUT_MS;
  while (Date.now() < deadline && !stopped) {
    if (await isUp(base)) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new UserError(`Transfer no ha arrancado. Revisa ${path.join(autostart.workDir, 'logs', 'transfer.log')}.`);
}

// Si la app local está parada la arranca; las subidas simultáneas comparten el mismo arranque
let starting = null;

async function ensureRunning({ base, token, autostart }) {
  if (!autostart || !LOCAL_HOSTS.has(base.hostname) || await isUp(base)) return;
  starting ??= startAndWait(autostart, base, token).finally(() => { starting = null; });
  await starting;
}

async function checkFile(filePath) {
  if (typeof filePath !== 'string' || !path.isAbsolute(filePath)) {
    throw new UserError(`La ruta debe ser absoluta: ${filePath}`);
  }
  let info;
  try {
    info = await stat(filePath);
  } catch {
    throw new UserError(`No existe o no se puede leer: ${filePath}`);
  }
  if (!info.isFile()) throw new UserError(`No es un fichero (las carpetas hay que comprimirlas antes): ${filePath}`);
  if (info.size === 0) throw new UserError(`El fichero está vacío: ${filePath}`);
  return info.size;
}

function describeFailure(status, body) {
  if (status === 401) return `Token rechazado por el servidor. Comprueba que API_TOKEN coincide con el de ${CONFIG_PATH}.`;
  if (status === 413) return 'El fichero supera el tamaño máximo de subida del servidor.';
  return body?.error ?? `El servidor respondió con el código ${status}.`;
}

// Envía el multipart leyendo del disco, sin cargar el fichero en memoria. Se usa http
// en lugar de fetch porque fetch tumba el proceso si el servidor responde antes de
// recibir todo el cuerpo (por ejemplo, con un 413).
function postFile(url, token, filePath, size) {
  const boundary = `----transfer${randomBytes(16).toString('hex')}`;
  const filename = path.basename(filePath).replace(/["\r\n]/g, '_');
  const head = Buffer.from(`--${boundary}\r\n`
    + `Content-Disposition: form-data; name="file"; filename="${filename}"\r\n`
    + 'Content-Type: application/octet-stream\r\n\r\n');
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`);

  return new Promise((resolve, reject) => {
    const file = createReadStream(filePath);
    const request = (url.protocol === 'https:' ? https : http).request(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Content-Length': head.length + size + tail.length,
      },
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('error', reject);
      response.on('end', () => {
        file.destroy();
        resolve({ status: response.statusCode, text: Buffer.concat(chunks).toString('utf8') });
      });
    });
    request.on('error', (e) => {
      file.destroy();
      reject(e);
    });
    file.on('error', (e) => request.destroy(e));
    file.on('end', () => request.end(tail));
    request.write(head);
    file.pipe(request, { end: false });
  });
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function upload(filePath) {
  const config = await loadConfig();
  const { base, token } = config;
  const size = await checkFile(filePath);
  await ensureRunning(config);

  let response;
  try {
    response = await postFile(new URL('api/files', base), token, filePath, size);
  } catch (e) {
    throw new UserError(`La subida a Transfer (${base}) ha fallado: ${e.code ?? e.message}`);
  }

  const body = parseJson(response.text);
  if (response.status !== 201) throw new UserError(describeFailure(response.status, body));
  return `Subido "${body.name}" (${body.formattedSize}). Disponible en ${new URL('?tab=descargas', base)}`;
}

async function callTool({ name, arguments: args }) {
  if (name !== TOOL.name) throw new RpcError(-32602, `Herramienta desconocida: ${name}`);
  try {
    return { content: [{ type: 'text', text: await upload(args?.path) }] };
  } catch (e) {
    if (!(e instanceof UserError)) console.error(e);
    return { content: [{ type: 'text', text: e.message }], isError: true };
  }
}

class RpcError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

async function handle(method, params) {
  switch (method) {
    case 'initialize':
      return {
        protocolVersion: params?.protocolVersion ?? '2025-06-18',
        capabilities: { tools: {} },
        serverInfo: { name: 'transfer', version: '1.0.0' },
      };
    case 'ping':
      return {};
    case 'tools/list':
      return { tools: [TOOL] };
    case 'tools/call':
      return callTool(params ?? {});
    default:
      throw new RpcError(-32601, `Método no soportado: ${method}`);
  }
}

function send(message) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', ...message })}\n`);
}

async function onLine(line) {
  if (!line.trim()) return;
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    send({ id: null, error: { code: -32700, message: 'JSON no válido' } });
    return;
  }
  // Las notificaciones no llevan id y no admiten respuesta
  if (request.id === undefined) return;
  try {
    send({ id: request.id, result: await handle(request.method, request.params) });
  } catch (e) {
    send({ id: request.id, error: { code: e.code ?? -32603, message: e.message } });
  }
}

readline.createInterface({ input: process.stdin }).on('line', onLine);
