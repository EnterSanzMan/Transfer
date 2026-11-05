document.addEventListener('DOMContentLoaded', () => {
    // 1. Manejo de Pestañas
    const tabButtons = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    function switchTab(targetTab) {
        tabButtons.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === targetTab);
        });
        tabContents.forEach(content => {
            content.classList.toggle('active', content.id === 'tab-' + targetTab);
        });

        const url = new URL(window.location);
        url.searchParams.set('tab', targetTab);
        window.history.replaceState({}, '', url);
    }

    tabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            switchTab(btn.dataset.tab);
        });
    });

    // 2. Filtrado y búsqueda rápida en tabla de descargas
    const searchInput = document.getElementById('searchFiles');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            const query = e.target.value.toLowerCase().trim();
            const rows = document.querySelectorAll('.files-table tbody tr');
            let visibleCount = 0;

            rows.forEach(row => {
                const name = row.getAttribute('data-filename') || '';
                if (name.includes(query)) {
                    row.style.display = '';
                    visibleCount++;
                } else {
                    row.style.display = 'none';
                }
            });

            const countIndicator = document.getElementById('visibleFilesCount');
            if (countIndicator) {
                countIndicator.textContent = visibleCount;
            }
        });
    }

    // 3. Subida de archivos / Drag and drop
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('fileInput');
    const selectedFileCard = document.getElementById('selectedFileCard');
    const selectedFileName = document.getElementById('selectedFileName');
    const selectedFileSize = document.getElementById('selectedFileSize');
    const uploadSubmitBtn = document.getElementById('uploadSubmitBtn');
    const uploadForm = document.getElementById('uploadForm');

    const uploadProgressContainer = document.getElementById('uploadProgressContainer');
    const uploadProgressBar = document.getElementById('uploadProgressBar');
    const uploadProgressPercent = document.getElementById('uploadProgressPercent');
    const uploadProgressStatus = document.getElementById('uploadProgressStatus');

    if (fileInput) {
        function formatBytes(bytes) {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }

        function handleFileSelection(file) {
            if (!file) return;
            selectedFileName.textContent = file.name;
            selectedFileSize.textContent = formatBytes(file.size);
            selectedFileCard.style.display = 'block';
            uploadSubmitBtn.removeAttribute('disabled');

            // Reiniciar estado de progreso si había uno anterior
            if (uploadProgressContainer) {
                uploadProgressContainer.style.display = 'none';
                uploadProgressBar.style.width = '0%';
                uploadProgressPercent.textContent = '0%';
                uploadProgressStatus.textContent = 'Subiendo...';
            }
            uploadSubmitBtn.innerHTML = '📤 Subir a Transfer';
        }

        fileInput.addEventListener('change', (e) => {
            if (e.target.files.length > 0) {
                handleFileSelection(e.target.files[0]);
            }
        });

        if (dropzone) {
            ['dragenter', 'dragover'].forEach(eventName => {
                dropzone.addEventListener(eventName, (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    dropzone.classList.add('dragover');
                }, false);
            });

            ['dragleave', 'drop'].forEach(eventName => {
                dropzone.addEventListener(eventName, (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    dropzone.classList.remove('dragover');
                }, false);
            });

            dropzone.addEventListener('drop', (e) => {
                const dt = e.dataTransfer;
                const files = dt.files;
                if (files.length > 0) {
                    fileInput.files = files;
                    handleFileSelection(files[0]);
                }
            });
        }

        if (uploadForm) {
            uploadForm.addEventListener('submit', (e) => {
                e.preventDefault();

                if (!fileInput.files || fileInput.files.length === 0) {
                    alert('Por favor selecciona un fichero');
                    return;
                }

                uploadSubmitBtn.setAttribute('disabled', 'true');
                uploadSubmitBtn.innerHTML = '⏳ Subiendo fichero...';

                if (uploadProgressContainer) {
                    uploadProgressContainer.style.display = 'flex';
                    uploadProgressBar.style.width = '0%';
                    uploadProgressPercent.textContent = '0%';
                    uploadProgressStatus.textContent = 'Subiendo...';
                }

                const formData = new FormData(uploadForm);
                const xhr = new XMLHttpRequest();
                xhr.open('POST', uploadForm.action, true);

                // Progreso de subida
                xhr.upload.onprogress = (event) => {
                    if (event.lengthComputable && uploadProgressBar) {
                        const percent = Math.round((event.loaded / event.total) * 100);
                        uploadProgressBar.style.width = percent + '%';
                        uploadProgressPercent.textContent = percent + '%';

                        if (percent >= 100) {
                            uploadProgressStatus.textContent = 'Guardando en servidor...';
                        }
                    }
                };

                xhr.onload = () => {
                    if (xhr.status >= 200 && xhr.status < 400) {
                        // Subida correcta: redirigir a descargas
                        window.location.href = xhr.responseURL || '/?tab=descargas';
                    } else {
                        alert('Error al subir el fichero (código ' + xhr.status + '). Puede que supere el tamaño máximo permitido.');
                        uploadSubmitBtn.removeAttribute('disabled');
                        uploadSubmitBtn.innerHTML = '📤 Subir a Transfer';
                        if (uploadProgressStatus) {
                            uploadProgressStatus.textContent = 'Error';
                        }
                    }
                };

                xhr.onerror = () => {
                    alert('Error de conexión durante la subida.');
                    uploadSubmitBtn.removeAttribute('disabled');
                    uploadSubmitBtn.innerHTML = '📤 Subir a Transfer';
                };

                xhr.send(formData);
            });
        }
    }

    // 4. Confirmación y refresco para 'Descargar y borrar'
    // Es un formulario POST: el servidor responde con el fichero y lo borra al terminar de enviarlo
    const downloadAndDeleteForms = document.querySelectorAll('.form-download-delete');
    downloadAndDeleteForms.forEach(form => {
        form.addEventListener('submit', (e) => {
            const filename = form.getAttribute('data-filename') || 'este fichero';
            const confirmed = confirm(`¿Seguro que deseas descargar y BORRAR permanentemente "${filename}" del servidor?`);
            if (!confirmed) {
                e.preventDefault();
                return;
            }

            // Ocultar fila visualmente y recargar tras inicio de descarga
            const row = form.closest('tr');
            if (row) {
                row.style.opacity = '0.4';
                row.style.pointerEvents = 'none';
            }

            // Recargar la tabla tras breve intervalo para reflejar el borrado
            setTimeout(() => {
                window.location.reload();
            }, 1800);
        });
    });
});
