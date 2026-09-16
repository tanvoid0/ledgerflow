package io.ledgerflow.settlement.batch;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Last day of the month, whatever the month's length — leap Februaries included. */
class MonthEndDeciderTest {

    private final MonthEndDecider decider = new MonthEndDecider();

    @ParameterizedTest
    @CsvSource({
            "2026-10-31, MONTH_END",
            "2026-10-30, ORDINARY_DAY",
            "2028-02-29, MONTH_END",
            "2027-02-28, MONTH_END",
    })
    void decidesOnTheCalendarMonthOfTheBusinessDate(String businessDate, String expected) {
        var params = new JobParametersBuilder().addString("businessDate", businessDate).toJobParameters();
        var execution = mock(JobExecution.class);
        when(execution.getJobParameters()).thenReturn(params);

        assertThat(decider.decide(execution, null).getName()).isEqualTo(expected);
    }
}
