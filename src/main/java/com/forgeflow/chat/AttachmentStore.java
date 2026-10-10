package com.forgeflow.chat;

import com.forgeflow.chat.dto.AttachmentInfo;
import com.forgeflow.shared.llm.ImagePart;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Images kept with chat messages. Plain JDBC: bytes in, bytes out, nothing to map. */
@Component
class AttachmentStore {

    record Stored(String mimeType, byte[] data) {
    }

    private final JdbcTemplate jdbc;

    AttachmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void save(Long messageId, String mimeType, byte[] data) {
        jdbc.update("INSERT INTO chat_attachments (message_id, mime_type, data, size_bytes) VALUES (?, ?, ?, ?)",
                messageId, mimeType, data, data.length);
    }

    /** For the model: every image of a message, as base64 parts. */
    List<ImagePart> imagesOf(Long messageId) {
        return jdbc.query("SELECT mime_type, data FROM chat_attachments WHERE message_id = ? ORDER BY id",
                (rs, i) -> new ImagePart(rs.getString(1), Base64.getEncoder().encodeToString(rs.getBytes(2))),
                messageId);
    }

    /** For history listings: metadata only, grouped by message, one query. */
    Map<Long, List<AttachmentInfo>> infoFor(Collection<Long> messageIds) {
        Map<Long, List<AttachmentInfo>> out = new LinkedHashMap<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT id, message_id, mime_type, size_bytes FROM chat_attachments WHERE message_id = ANY(?) ORDER BY id",
                rs -> {
                    out.computeIfAbsent(rs.getLong("message_id"), k -> new ArrayList<>())
                       .add(new AttachmentInfo(rs.getLong("id"), rs.getString("mime_type"), rs.getInt("size_bytes")));
                }, (Object) messageIds.toArray(Long[]::new));
        return out;
    }

    Optional<Stored> load(Long messageId, Long attachmentId) {
        return jdbc.query("SELECT mime_type, data FROM chat_attachments WHERE id = ? AND message_id = ?",
                rs -> rs.next() ? Optional.of(new Stored(rs.getString(1), rs.getBytes(2))) : Optional.empty(),
                attachmentId, messageId);
    }
}
