package com.bookleaf.support.repository;

import com.bookleaf.support.domain.Attachment;
import com.bookleaf.support.dto.AttachmentInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** Metadata only: file content is never loaded for a listing. */
    @Query("select new com.bookleaf.support.dto.AttachmentInfo(a.id, a.fileName, a.contentType, a.sizeBytes, a.createdAt) "
            + "from Attachment a where a.ticket.id = :ticketId order by a.createdAt asc, a.id asc")
    List<AttachmentInfo> findInfoByTicketId(@Param("ticketId") UUID ticketId);

    long countByTicket_Id(UUID ticketId);

    Optional<Attachment> findByIdAndTicket_Id(UUID id, UUID ticketId);
}
