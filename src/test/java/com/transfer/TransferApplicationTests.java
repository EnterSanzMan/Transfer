package com.transfer;

import com.transfer.model.AppUser;
import com.transfer.model.StoredFile;
import com.transfer.repository.StoredFileRepository;
import com.transfer.repository.UserRepository;
import com.transfer.security.LoginAttemptService;
import com.transfer.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/test-data/transfer-test.db",
        "transfer.upload-dir=./target/test-uploads",
        "transfer.initial-admin.username=admin",
        "transfer.initial-admin.password=clave-de-test",
        "transfer.max-storage-mb=1",
        "transfer.min-free-disk-mb=0",
        "transfer.login.max-attempts=3",
        "transfer.login.lock-minutes=15",
        "transfer.api-token=" + TransferApplicationTests.API_TOKEN
})
class TransferApplicationTests {

    static final String API_TOKEN = "token-de-test-con-mas-de-32-caracteres";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void cleanFiles() {
        storedFileRepository.findAll().forEach(f -> fileStorageService.deleteFileById(f.getId()));
    }

    @Test
    void initialAdminIsCreatedWithConfiguredPassword() {
        Optional<AppUser> userOpt = userRepository.findByUsername("admin");
        assertTrue(userOpt.isPresent(), "El admin inicial debe existir");
        assertEquals("ROLE_ADMIN", userOpt.get().getRole());
        assertTrue(passwordEncoder.matches("clave-de-test", userOpt.get().getPassword()));
    }

    @Test
    void storeFileAndMetadata() throws IOException {
        StoredFile stored = fileStorageService.store(file("documento_prueba.pdf", "Contenido de prueba"));
        assertNotNull(stored.getId());
        assertEquals("documento_prueba.pdf", stored.getOriginalFilename());
        assertTrue(stored.getSize() > 0);
        assertNotNull(stored.getFormattedSize());
        assertNotNull(stored.getUploadDate());

        Path diskFile = fileStorageService.loadFilePath(stored);
        assertTrue(Files.exists(diskFile), "El fichero debe existir en disco");

        fileStorageService.deleteFileById(stored.getId());
        assertFalse(Files.exists(diskFile), "El fichero no debe existir en disco tras borrado");
    }

    @Test
    void purgeFilesOlderThanRetention() throws IOException {
        StoredFile stored = fileStorageService.store(file("antiguo_archivo.txt", "Fichero viejo"));
        stored.setUploadDate(LocalDateTime.now().minusDays(35));
        storedFileRepository.save(stored);
        Path diskPath = fileStorageService.loadFilePath(stored);

        assertTrue(fileStorageService.purgeExpiredFiles() >= 1);
        assertFalse(storedFileRepository.findById(stored.getId()).isPresent(), "No debe estar en la BBDD");
        assertFalse(Files.exists(diskPath), "No debe existir en disco");
    }

    @Test
    void uploadOverQuotaIsRejected() throws Exception {
        byte[] big = new byte[700 * 1024];
        fileStorageService.store(new MockMultipartFile("file", "a.bin", "application/octet-stream", big));

        // La cuota de test es 1 MB: un segundo fichero de 700 KB no cabe
        mvc.perform(multipart("/files/upload")
                        .file(new MockMultipartFile("file", "b.bin", "application/octet-stream", big))
                        .with(csrf()).with(user()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", org.hamcrest.Matchers.containsString("No queda espacio")));
        assertEquals(1, storedFileRepository.count());
    }

    @Test
    @WithMockUser
    void downloadAndDeleteRejectsGet() throws Exception {
        StoredFile stored = fileStorageService.store(file("x.txt", "hola"));

        mvc.perform(get("/files/{id}/download-and-delete", stored.getId()))
                .andExpect(status().isMethodNotAllowed());
        assertTrue(storedFileRepository.findById(stored.getId()).isPresent(), "Un GET no debe borrar nada");
    }

    @Test
    @WithMockUser
    void downloadAndDeleteRequiresCsrfToken() throws Exception {
        StoredFile stored = fileStorageService.store(file("x.txt", "hola"));

        mvc.perform(post("/files/{id}/download-and-delete", stored.getId()))
                .andExpect(status().isForbidden());
        assertTrue(storedFileRepository.findById(stored.getId()).isPresent());
    }

    @Test
    @WithMockUser
    void downloadAndDeleteSendsFileAndDeletesIt() throws Exception {
        StoredFile stored = fileStorageService.store(file("x.txt", "hola"));
        Path diskPath = fileStorageService.loadFilePath(stored);

        MvcResult started = mvc.perform(post("/files/{id}/download-and-delete", stored.getId()).with(csrf()))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string("hola"));

        assertFalse(storedFileRepository.findById(stored.getId()).isPresent());
        assertFalse(Files.exists(diskPath));
    }

    @Test
    void loginIsBlockedAfterRepeatedFailures() throws Exception {
        String ip = "203.0.113.7";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/login").with(csrf()).with(remoteAddr(ip))
                            .param("username", "admin").param("password", "mala"))
                    .andExpect(redirectedUrl("/login?error=true"));
        }
        assertTrue(loginAttemptService.isBlocked(ip));

        // Bloqueada: ni con la clave buena entra
        mvc.perform(post("/login").with(csrf()).with(remoteAddr(ip))
                        .param("username", "admin").param("password", "clave-de-test"))
                .andExpect(redirectedUrl("/login?blocked=true"));

        // Otra IP sí puede entrar
        mvc.perform(post("/login").with(csrf()).with(remoteAddr("203.0.113.8"))
                        .param("username", "ADMIN").param("password", "clave-de-test"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void apiUploadWithValidTokenStoresFile() throws Exception {
        mvc.perform(multipart("/api/files").file(file("informe.txt", "hola"))
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("informe.txt"))
                .andExpect(jsonPath("$.size").value(4));
        assertEquals(1, storedFileRepository.count());
    }

    @Test
    void apiUploadWithoutValidTokenIsRejected() throws Exception {
        mvc.perform(multipart("/api/files").file(file("a.txt", "hola")))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/files").file(file("a.txt", "hola"))
                        .header("Authorization", "Bearer token-equivocado"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, storedFileRepository.count());
    }

    @Test
    void apiUploadOverQuotaReturnsInsufficientStorage() throws Exception {
        byte[] big = new byte[700 * 1024];
        fileStorageService.store(new MockMultipartFile("file", "a.bin", "application/octet-stream", big));

        mvc.perform(multipart("/api/files")
                        .file(new MockMultipartFile("file", "b.bin", "application/octet-stream", big))
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("No queda espacio")));
    }

    @Test
    void apiTokenDoesNotOpenTheWebInterface() throws Exception {
        mvc.perform(get("/").header("Authorization", "Bearer " + API_TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("usuario");
    }
}
