package io.ledgerflow.settlement.batch;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.integration.chunk.RemoteChunkingManagerStepBuilder;
import org.springframework.batch.integration.chunk.RemoteChunkingWorkerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.kafka.dsl.Kafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * The other rung: instead of a whole worker step per partition, one manager step ships 100-item chunks to
 * whichever worker is free and collects the results back. A separate job, run once, never in the nightly
 * graph — 2026-11-05's 1,000 rows is the only date that uses it. Payloads are JDK-serialised (Batch's
 * ChunkRequest/ChunkResponse carry no Jackson mapping), so both directions get their own byte[] producer and
 * consumer, built here rather than as beans: a second KafkaTemplate/ConsumerFactory/ProducerFactory bean of
 * any generic shape would satisfy Boot's @ConditionalOnMissingBean and back off the String ones the outbox
 * publisher and every @KafkaListener depend on.
 */
@Configuration
class RemoteChunkingConfig {

    static final String CHUNK_REQUESTS_TOPIC = "ledgerflow.settlement.chunk-requests.v1";
    static final String CHUNK_REPLIES_TOPIC = "ledgerflow.settlement.chunk-replies.v1";
    // the batch types on the wire (ChunkRequest/ChunkResponse -> StepContribution -> ...) plus this service's own items
    private static final String[] ALLOWED_CLASSES = {"org.springframework.batch.*", "io.ledgerflow.*", "java.*"};

    // unconditional: the job is always registered (GET /api/v1/batch/jobs lists it in every mode), only the
    // flows that move requests across the broker are profile-gated
    @Bean
    DirectChannel chunkRequestsOut() {
        return new DirectChannel();
    }

    @Bean
    QueueChannel chunkReplies() {
        return new QueueChannel();
    }

    // no .processor() here on purpose: the manager only reads and ships: no processor means the chunk goes out
    // as-read (SettlementItem "passed through" as O), and the worker's itemProcessor is what actually calls the
    // FX gateway. A manager-side processor would run the FX call locally and ship a SettlementLine instead -
    // exactly the per-item cost remote chunking exists to spread, and SettlementLine isn't Serializable anyway.
    @Bean
    TaskletStep lineItemsChunkingStep(JobRepository jobRepository, PlatformTransactionManager tx,
                                       JdbcPagingItemReader<SettlementItem> pagingItemReader,
                                       DirectChannel chunkRequestsOut, QueueChannel chunkReplies) {
        return new RemoteChunkingManagerStepBuilder<SettlementItem, SettlementLine>("lineItemsChunkingStep", jobRepository)
                .transactionManager(tx)
                .reader(pagingItemReader)
                .outputChannel(chunkRequestsOut)
                .inputChannel(chunkReplies)
                .chunk(100)
                .build();
    }

    @Bean
    Job lineItemsChunkingJob(JobRepository jobRepository, Step lineItemsChunkingStep) {
        return new JobBuilder("lineItemsChunkingJob", jobRepository).start(lineItemsChunkingStep).build();
    }

    /** Manager: requests out, JDK-serialised. */
    @Bean
    @Profile("manager")
    IntegrationFlow chunkRequestsOutboundFlow(DirectChannel chunkRequestsOut, ProducerFactory<?, ?> bootProducers) {
        var kafka = new KafkaTemplate<>(byteProducerFactory(bootProducers));
        return IntegrationFlow.from(chunkRequestsOut)
                .transform(Transformers.serializer())
                .handle(Kafka.outboundChannelAdapter(kafka).topic(CHUNK_REQUESTS_TOPIC))
                .get();
    }

    /** Manager: replies in, on its own group — one manager JVM at a time, or a second one eats the first's replies. */
    @Bean
    @Profile("manager")
    IntegrationFlow chunkRepliesInboundFlow(QueueChannel chunkReplies, ConsumerFactory<?, ?> bootConsumers) {
        var container = byteListenerContainer(bootConsumers, "settlement-chunk-manager", CHUNK_REPLIES_TOPIC);
        return IntegrationFlow.from(Kafka.messageDrivenChannelAdapter(container))
                .transform(Transformers.deserializer(ALLOWED_CLASSES))
                .channel(chunkReplies)
                .get();
    }

    @Bean
    @Profile("worker")
    DirectChannel chunkRequestsIn() {
        return new DirectChannel();
    }

    @Bean
    @Profile("worker")
    DirectChannel chunkRepliesOut() {
        return new DirectChannel();
    }

    /** Worker: processes and writes exactly like the in-JVM/partition modes, just fed from the wire instead of a reader. */
    @Bean
    @Profile("worker")
    IntegrationFlow lineItemsChunkingWorkerFlow(DirectChannel chunkRequestsIn, DirectChannel chunkRepliesOut,
                                                 ItemProcessor<SettlementItem, SettlementLine> lineProcessor,
                                                 ItemWriter<SettlementLine> routedLineWriter) {
        return new RemoteChunkingWorkerBuilder<SettlementItem, SettlementLine>()
                .itemProcessor(lineProcessor)
                .itemWriter(routedLineWriter)
                .inputChannel(chunkRequestsIn)
                .outputChannel(chunkRepliesOut)
                .build();
    }

    @Bean
    @Profile("worker")
    IntegrationFlow chunkRequestsInboundFlow(DirectChannel chunkRequestsIn, ConsumerFactory<?, ?> bootConsumers) {
        var container = byteListenerContainer(bootConsumers, "settlement-chunk-worker", CHUNK_REQUESTS_TOPIC);
        return IntegrationFlow.from(Kafka.messageDrivenChannelAdapter(container))
                .transform(Transformers.deserializer(ALLOWED_CLASSES))
                .channel(chunkRequestsIn)
                .get();
    }

    @Bean
    @Profile("worker")
    IntegrationFlow chunkRepliesOutboundFlow(DirectChannel chunkRepliesOut, ProducerFactory<?, ?> bootProducers) {
        var kafka = new KafkaTemplate<>(byteProducerFactory(bootProducers));
        return IntegrationFlow.from(chunkRepliesOut)
                .transform(Transformers.serializer())
                .handle(Kafka.outboundChannelAdapter(kafka).topic(CHUNK_REPLIES_TOPIC))
                .get();
    }

    // copied from Boot's own factories, not rebuilt from KafkaProperties: theirs carry the connection details
    // (KAFKA_BOOTSTRAP, a test's @ServiceConnection); KafkaProperties alone sent RemoteWorkersIT's chunks to
    // whatever broker held localhost:9092 - the kind cluster's, whose workers took them
    private static DefaultKafkaProducerFactory<byte[], byte[]> byteProducerFactory(ProducerFactory<?, ?> boot) {
        Map<String, Object> config = new HashMap<>(boot.getConfigurationProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    private static ConcurrentMessageListenerContainer<byte[], byte[]> byteListenerContainer(ConsumerFactory<?, ?> boot, String groupId, String topic) {
        Map<String, Object> config = new HashMap<>(boot.getConfigurationProperties());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        var consumerFactory = new DefaultKafkaConsumerFactory<byte[], byte[]>(config);
        var containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(groupId);
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        return new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
    }
}
