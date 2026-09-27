package io.ledgerflow.settlement.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchExecutionControllerTest {

    @Test
    void anUnknownExecutionIsA404NotA500() {
        var repository = mock(JobRepository.class);
        when(repository.getJobExecution(999L)).thenThrow(new EmptyResultDataAccessException(1));
        var controller = new BatchExecutionController(mock(JobOperator.class), repository);

        assertThatThrownBy(() -> controller.stop(999L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
