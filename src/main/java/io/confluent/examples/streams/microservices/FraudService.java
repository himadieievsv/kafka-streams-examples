package io.confluent.examples.streams.microservices;

import io.confluent.examples.streams.avro.microservices.Order;
import io.confluent.examples.streams.avro.microservices.OrderState;
import io.confluent.examples.streams.avro.microservices.OrderValidation;
import io.confluent.examples.streams.avro.microservices.OrderValue;
import io.confluent.examples.streams.microservices.domain.Schemas;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.KafkaStreams.State;

import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;

import static io.confluent.examples.streams.avro.microservices.OrderValidationResult.FAIL;
import static io.confluent.examples.streams.avro.microservices.OrderValidationResult.PASS;
import static io.confluent.examples.streams.avro.microservices.OrderValidationType.FRAUD_CHECK;
import static io.confluent.examples.streams.microservices.domain.Schemas.Topics.ORDERS;
import static io.confluent.examples.streams.microservices.domain.Schemas.Topics.ORDER_VALIDATIONS;
import static io.confluent.examples.streams.microservices.util.MicroserviceUtils.addShutdownHookAndBlock;
import static io.confluent.examples.streams.microservices.util.MicroserviceUtils.baseStreamsConfig;
import static io.confluent.examples.streams.microservices.util.MicroserviceUtils.parseArgsAndConfigure;


/**
 * This service searches for potentially fraudulent transactions by calculating the total value of
 * orders for a customer within a time period, then checks to see if this is over a configured
 * limit. <p> i.e. if(SUM(order.value, 5Mins) > $5000) GroupBy customer -> Fail(orderId) else
 * Pass(orderId)
 */
public class FraudService implements Service {

  private static final Logger log = LoggerFactory.getLogger(FraudService.class);
  private final String SERVICE_APP_ID = getClass().getSimpleName();

  private static final int FRAUD_LIMIT = 2000;
  private KafkaStreams streams;

  @Override
  public void start(final String bootstrapServers, final String stateDir) {
    streams = processStreams(bootstrapServers, stateDir);
    streams.cleanUp(); //don't do this in prod as it clears your state stores
    final CountDownLatch startLatch = new CountDownLatch(1);
    streams.setStateListener((newState, oldState) -> {
      if (newState == State.RUNNING && oldState == State.REBALANCING) {
        startLatch.countDown();
      }

    });
    streams.start();

    try {
      if (!startLatch.await(60, TimeUnit.SECONDS)) {
        throw new RuntimeException("Streams never finished rebalancing on startup");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.info("Started Service " + getClass().getSimpleName());
  }

  private KafkaStreams processStreams(final String bootstrapServers, final String stateDir) {

    //Latch onto instances of the orders and inventory topics
    final StreamsBuilder builder = new StreamsBuilder();
    final KStream<String, Order> orders = builder
        .stream(ORDERS.name(), Consumed.with(ORDERS.keySerde(), ORDERS.valueSerde()))
        .filter((id, order) -> OrderState.CREATED.equals(order.getState()));

    //Create an aggregate of the total value by customer and hold it with the order. We use session windows to
    // detect periods of activity.
    final KTable<Windowed<Long>, OrderValue> aggregate = orders
        .groupBy((id, order) -> order.getCustomerId(), Grouped.with(Serdes.Long(), ORDERS.valueSerde()))
        .windowedBy(SessionWindows.with(Duration.ofHours(1)))
        .aggregate(OrderValue::new,
            //Calculate running total for each customer within this window
            (custId, order, total) -> new OrderValue(order,
                total.getValue() + order.getQuantity() * order.getPrice()),
            (k, a, b) -> simpleMerge(a, b), //include a merger as we're using session windows.
            Materialized.with(null, Schemas.ORDER_VALUE_SERDE));

    //Ditch the windowing and rekey
    final KStream<String, OrderValue> ordersWithTotals = aggregate
        .toStream((windowedKey, orderValue) -> windowedKey.key())
        .filter((k, v) -> v != null)//When elements are evicted from a session window they create delete events. Filter these out.
        .selectKey((id, orderValue) -> orderValue.getOrder().getId());

    //Now branch the stream into two, for pass and fail, based on whether the windowed total is over Fraud Limit
    @SuppressWarnings("unchecked")
    final KStream<String, OrderValue>[] forks = ordersWithTotals.branch(
        (id, orderValue) -> orderValue.getValue() >= FRAUD_LIMIT,
        (id, orderValue) -> orderValue.getValue() < FRAUD_LIMIT);

    forks[0].mapValues(
        orderValue -> new OrderValidation(orderValue.getOrder().getId(), FRAUD_CHECK, FAIL))
        .to(ORDER_VALIDATIONS.name(), Produced
            .with(ORDER_VALIDATIONS.keySerde(), ORDER_VALIDATIONS.valueSerde()));

    forks[1].mapValues(
        orderValue -> new OrderValidation(orderValue.getOrder().getId(), FRAUD_CHECK, PASS))
        .to(ORDER_VALIDATIONS.name(), Produced
            .with(ORDER_VALIDATIONS.keySerde(), ORDER_VALIDATIONS.valueSerde()));

    //disable caching to ensure a complete aggregate changelog. This is a little trick we need to apply
    //as caching in Kafka Streams will conflate subsequent updates for the same key. Disabling caching ensures
    //we get a complete "changelog" from the aggregate(...) step above (i.e. every input event will have a
    //corresponding output event.
    final Properties props = baseStreamsConfig(bootstrapServers, stateDir, SERVICE_APP_ID);
    props.setProperty(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, "0");

    return new KafkaStreams(builder.build(), props);
  }

  private OrderValue simpleMerge(final OrderValue a, final OrderValue b) {
    return new OrderValue(b.getOrder(), (a == null ? 0D : a.getValue()) + b.getValue());
  }

  public static void main(final String[] args) throws Exception {
    final FraudService service = new FraudService();
    service.start(parseArgsAndConfigure(args), "/tmp/kafka-streams");
    addShutdownHookAndBlock(service);
  }

  @Override
  public void stop() {
    if (streams != null) {
      streams.close();
    }
  }

}
