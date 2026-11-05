package com.transfer.repository;

import com.transfer.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findAllByOrderByUploadDateDesc();
    List<StoredFile> findByUploadDateBefore(LocalDateTime cutoff);

    @Query("select coalesce(sum(f.size), 0) from StoredFile f")
    long totalSize();
}
