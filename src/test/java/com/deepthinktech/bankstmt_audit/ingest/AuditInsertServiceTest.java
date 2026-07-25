package com.deepthinktech.bankstmt_audit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.deepthinktech.bankstmt_audit.domain.AuditEvent;
import com.deepthinktech.bankstmt_audit.repo.AuditEventRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditInsertServiceTest {

    @Mock
    AuditEventRepository repository;

    @InjectMocks
    AuditInsertService service;

    private AuditMessage message(String eventId) {
        return new AuditMessage(eventId, "t1", "u1", "POST /api/statements",
            null, null, "1.2.3.4", "curl", "req-1", "status=200", OffsetDateTime.now().toString());
    }

    @Test
    void insertsNewEvent() {
        when(repository.existsById("e1")).thenReturn(false);

        boolean inserted = service.insert(message("e1"));

        assertThat(inserted).isTrue();
        verify(repository).save(any(AuditEvent.class));
    }

    @Test
    void skipsWhenAlreadyPresent() {
        when(repository.existsById("e1")).thenReturn(true);

        boolean inserted = service.insert(message("e1"));

        assertThat(inserted).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsMissingEventIdSoItReachesTheDlq() {
        assertThatThrownBy(() -> service.insert(message(null)))
            .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void firstEventStartsTheChainAtGenesis() {
        when(repository.existsById("e1")).thenReturn(false);
        when(repository.findTopByOrderBySeqDesc()).thenReturn(null);

        service.insert(message("e1"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getSeq()).isEqualTo(1L);
        assertThat(saved.getPrevHash()).isEqualTo("0".repeat(64));
        assertThat(saved.getEntryHash()).hasSize(64);
    }

    @Test
    void chainsEachEventToThePrevious() {
        AuditEvent last = new AuditEvent();
        last.setSeq(41L);
        last.setEntryHash("a".repeat(64));
        when(repository.existsById("e2")).thenReturn(false);
        when(repository.findTopByOrderBySeqDesc()).thenReturn(last);

        service.insert(message("e2"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getSeq()).isEqualTo(42L);
        assertThat(saved.getPrevHash()).isEqualTo("a".repeat(64));
        assertThat(saved.getEntryHash()).hasSize(64).isNotEqualTo("a".repeat(64));
    }
}
