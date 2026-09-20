package io.ledgerflow.settlement.batch;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.integration.partition.RemotePartitioningWorkerStepBuilder;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.kafka.dsl.Kafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The wire for remote partitioning: the manager (lineItemsStep's REMOTE case, in SettlementJobConfig) drops a
 * StepExecutionRequest per partition onto partitionRequestsOut and then polls batch_step_execution for the
 * result; it never reads a reply channel. The worker side only exists on a pod running the worker profile.
 */
@Configuration
class RemotePartitioningConfig {

    static final String PARTITION_REQUESTS_TOPIC = "ledgerflow.settlement.partition-requests.v1";
    private static final String WORKER_GROUP = "settlement-worker";

    // Unconditional: lineItemsStep injects partitionRequestsOut by name in every mode, remote or not.
    @Bean
    DirectChannel partitionRequestsOut() {
        return new DirectChannel();
    }

    @Bean
    DirectChannel partitionRequestsIn() {
        return new DirectChannel();
    }

    /** Manager: a StepExecutionRequest goes out as JSON, keyed by its step execution id so eight keys spread over the topic's eight partitions. */
    @Bean
    @ConditionalOnProperty(prefix = "ledgerflow.settlement", name = "line-items", havingValue = "remote")
    IntegrationFlow partitionRequestsOutboundFlow(DirectChannel partitionRequestsOut, KafkaTemplate<String, String> kafka) {
        return IntegrationFlow.from(partitionRequestsOut)
                .enrichHeaders(h -> h.headerFunction(KafkaHeaders.KEY,
                        m -> Long.toString(((StepExecutionRequest) m.getPayload()).getStepExecutionId())))
                .transform(Transformers.toJson())
                // Transformers.toJson() adds Jackson type headers for a receiver with no target class; ours (fromJson(StepExecutionRequest.class))
                // has one, and the default header mapper can't JSON-encode json_resolvableType's self-referencing ResolvableType to send it anyway.
                .headerFilter("json*")
                .handle(Kafka.outboundChannelAdapter(kafka).topic(PARTITION_REQUESTS_TOPIC))
                .get();
    }

    /**
     * Worker: its own group (settlement-worker, not settlement-service — step 19's lesson), concurrency 2 so
     * four pods cover the topic's eight partitions, RECORD acks so an offset only commits once its partition
     * is done, and max.poll.interval.ms matched to the manager's ten-minute timeout so a partition still
     * running doesn't get its consumer kicked before the manager itself would give up.
     */
    @Bean
    @Profile("worker")
    @ConditionalOnProperty(prefix = "ledgerflow.settlement", name = "line-items", havingValue = "remote")
    ConcurrentMessageListenerContainer<String, String> partitionRequestsContainer(ConsumerFactory<String, String> consumerFactory) {
        var containerProps = new ContainerProperties(PARTITION_REQUESTS_TOPIC);
        containerProps.setGroupId(WORKER_GROUP);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProps.getKafkaConsumerProperties().setProperty("max.poll.interval.ms", "600000");
        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        container.setConcurrency(2);
        return container;
    }

    @Bean
    @Profile("worker")
    @ConditionalOnProperty(prefix = "ledgerflow.settlement", name = "line-items", havingValue = "remote")
    IntegrationFlow partitionRequestsInboundFlow(ConcurrentMessageListenerContainer<String, String> partitionRequestsContainer,
                                                  DirectChannel partitionRequestsIn) {
        return IntegrationFlow.from(Kafka.messageDrivenChannelAdapter(partitionRequestsContainer))
                .transform(Transformers.fromJson(StepExecutionRequest.class))
                .channel(partitionRequestsIn)
                .get();
    }

    /** The worker's lineItemsWorkerStep: same bean name as SettlementJobConfig's in-JVM one (BeanFactoryStepLocator resolves the request's step name as a bean name), off the wire instead of a partitioned reader. */
    @Bean
    @Profile("worker")
    Step lineItemsWorkerStep(JobRepository jobRepository, PlatformTransactionManager tx, BeanFactory beanFactory,
                             DirectChannel partitionRequestsIn, JdbcPagingItemReader<SettlementItem> partitionedItemReader,
                             ItemProcessor<SettlementItem, SettlementLine> lineProcessor, ItemWriter<SettlementLine> routedLineWriter,
                             RetryPolicy transientOnly, RejectUnsettleableItems rejectUnsettleableItems, SettlementMetrics metrics) {
        var builder = new RemotePartitioningWorkerStepBuilder("lineItemsWorkerStep", jobRepository)
                .inputChannel(partitionRequestsIn).beanFactory(beanFactory);
        return SettlementJobConfig.chunkStep(builder, tx, partitionedItemReader, lineProcessor, routedLineWriter, transientOnly,
                rejectUnsettleableItems, metrics, null, null);
    }
}
