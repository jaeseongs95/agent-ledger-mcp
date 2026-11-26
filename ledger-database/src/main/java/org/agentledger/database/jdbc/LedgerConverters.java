// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.agentledger.database.time.UtcTime;
import org.agentledger.domain.value.ActorId;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.domain.value.CodeValue;
import org.agentledger.domain.value.CriterionId;
import org.agentledger.domain.value.IssueId;
import org.agentledger.domain.value.JsonDocument;
import org.agentledger.domain.value.ProjectCode;
import org.agentledger.domain.value.ProjectId;
import org.agentledger.domain.value.OperationKey;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.SpecId;
import org.agentledger.domain.value.TaskId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * Every converter between Java values and columns, in one place. Times go through {@link UtcTime} only. Value
 * records are stored as their text; JSON documents are stored as their text in a long text column.
 */
final class LedgerConverters {

    private LedgerConverters() {
    }

    static List<Object> all() {
        List<Object> converters = new ArrayList<>();
        converters.add(new InstantWriter());
        converters.add(new LocalDateTimeReader());
        converters.add(new TimestampReader());
        pair(converters, ProjectId.class, ProjectId::new, ProjectId::value);
        pair(converters, ProjectCode.class, ProjectCode::new, ProjectCode::value);
        pair(converters, IssueId.class, IssueId::new, IssueId::value);
        pair(converters, SpecId.class, SpecId::new, SpecId::value);
        pair(converters, TaskId.class, TaskId::new, TaskId::value);
        pair(converters, CriterionId.class, CriterionId::new, CriterionId::value);
        pair(converters, CodeValue.class, CodeValue::new, CodeValue::value);
        pair(converters, Sha256.class, Sha256::new, Sha256::hex);
        pair(converters, OperationKey.class, OperationKey::parse, OperationKey::toString);
        pair(converters, AuthorId.class, AuthorId::new, AuthorId::value);
        pair(converters, ActorId.class, ActorId::ofStored, ActorId::value);
        pair(converters, JsonDocument.class, JsonDocument::new, JsonDocument::text);
        return converters;
    }

    private static <T> void pair(List<Object> into, Class<T> type, Function<String, T> read, Function<T, String> write) {
        into.add(new StringValueReader<>(type, read));
        into.add(new StringValueWriter<>(type, write));
    }

    /** Instant to the zone-less UTC LocalDateTime, bound with JDBC 4.2 setObject. */
    @WritingConverter
    static final class InstantWriter implements Converter<Instant, LocalDateTime> {
        @Override
        public LocalDateTime convert(Instant source) {
            return UtcTime.toDb(source);
        }
    }

    /** For drivers that return LocalDateTime from getObject. */
    @ReadingConverter
    static final class LocalDateTimeReader implements Converter<LocalDateTime, Instant> {
        @Override
        public Instant convert(LocalDateTime source) {
            return UtcTime.fromDb(source);
        }
    }

    /**
     * For drivers that return java.sql.Timestamp from getObject. The driver built it from the stored wall clock in
     * the JVM (or session) zone, so toLocalDateTime() restores the stored wall clock; that is exact when both
     * zones are UTC, which the startup guard checks.
     */
    @ReadingConverter
    static final class TimestampReader implements Converter<Timestamp, Instant> {
        @Override
        public Instant convert(Timestamp source) {
            return UtcTime.fromDb(source.toLocalDateTime());
        }
    }
}
