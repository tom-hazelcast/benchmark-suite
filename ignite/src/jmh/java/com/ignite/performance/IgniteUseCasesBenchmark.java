package com.ignite.performance;

import com.ignite.poc.model.RiskTrade;
import com.ignite.poc.query.RiskTradeBookScanQuery;
import com.ignite.poc.query.RiskTradeSettleCurrencyScanQuery;
import com.ignite.poc.query.RiskTradeThreeFieldScanQuery;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ignite.benchmark.common.IgniteBenchmarkHelper.fetchAllRecordsOneByOne;
import static com.ignite.performance.support.DummyData.getMeDummyRiskTrades;
import static common.BenchmarkConstants.*;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
//@BenchmarkMode({Mode.AverageTime, Mode.SingleShotTime})
@BenchmarkMode({Mode.SampleTime})
public class IgniteUseCasesBenchmark
{
    private static Logger logger = LoggerFactory.getLogger(IgniteUseCasesBenchmark.class);
    private static final String ADDRESSES_PROPERTY_NAME = "benchmark.ignite.discovery.addresses";
    private static final String PORTS_PROPERTY_NAME = "benchmark.ignite.discovery.ports";
    private static final String HOSTS_DEFAULT = "127.0.0.1"; // comma separated
    private static final String PORTS_DEFAULT = "47500..47509";
    private static final String CLIENT_NAME = "BenchmarkClient-";

    @State(Scope.Thread)
    public static class InitReadCacheState
    {
        private IgniteClient igniteClient;
        private ClientCache<Integer, RiskTrade> riskTradeReadCache;
        private ClientCache<Integer, RiskTrade> riskTradeOffHeapCache;
        private List<RiskTrade> riskTradeList;

        private ThreadLocalRandom randomizer = ThreadLocalRandom.current();

        @Setup(Level.Trial)
        public void before()
        {
            String addressesString = System.getProperty("benchmark.ignite.addresses", "127.0.0.1:40100");
            ClientConfiguration cfg = new ClientConfiguration().setAddresses(addressesString);
            this.igniteClient = Ignition.startClient(cfg);
            this.riskTradeReadCache = igniteClient.cache(TRADE_READ_MAP);
            this.riskTradeOffHeapCache = igniteClient.cache(TRADE_OFFHEAP_MAP);
            this.riskTradeList = getMeDummyRiskTrades();
            populateMap(riskTradeReadCache, riskTradeList);
        }

        @TearDown(Level.Trial)
        public void afterAll()
        {
            try
            {
                igniteClient.close();
            }
            catch (Exception e)
            {
                logger.error(e.getLocalizedMessage());
            }
        }

    }

    private static void putAllRiskTradesInBulk(Blackhole blackhole, ClientCache<Integer, RiskTrade> riskTradeCache, List<RiskTrade> riskTradeList, int startIndex, int batchSize)
    {
        Map<Integer, RiskTrade> trades = new HashMap<Integer, RiskTrade>();
        for(int i = startIndex; i < batchSize && i < riskTradeList.size(); i++)
        {
            RiskTrade riskTrade = riskTradeList.get(i);
            trades.put(riskTrade.getId(), riskTrade);
        }
        riskTradeCache.putAll(trades);
    }

    private static void populateMap(ClientCache<Integer, RiskTrade> workCache, List<RiskTrade> riskTradeList)
    {
        for (RiskTrade riskTrade : riskTradeList)
        {
            workCache.put(riskTrade.getId(), riskTrade);
        }
    }

    private static Set<Integer> getAllKeys(ClientCache<Integer, RiskTrade> cache)
    {
        Set<Integer> keys = new HashSet<>();
        cache.query(new ScanQuery<>(null)).forEach(entry -> keys.add((Integer) entry.getKey()));
        return keys;
    }

    //region FIXTURE
    @Benchmark
    @Measurement(iterations = 100000)
    @Warmup(iterations = 5)
    public void b01_InsertTradesSingle(Blackhole blackhole, InitReadCacheState state) throws Exception
    {
        RiskTrade riskTrade = state.riskTradeList.get(state.randomizer.nextInt(state.riskTradeList.size()));
        state.riskTradeOffHeapCache.put(riskTrade.getId(), riskTrade);
    }

    @Benchmark
    @Measurement(iterations = 1000)
    @Warmup(iterations = 5)
    public void b02_InsertTradesBulk(Blackhole blackhole, InitReadCacheState state) throws Exception
    {
        int startIndex = state.randomizer.nextInt(state.riskTradeList.size() - BATCH_SIZE);
        putAllRiskTradesInBulk(blackhole, state.riskTradeOffHeapCache, state.riskTradeList, startIndex, BATCH_SIZE);
    }

    @Benchmark
    public void b03_GetAllTradesSingle(Blackhole blackhole, InitReadCacheState state) throws Exception
    {
        fetchAllRecordsOneByOne(state.riskTradeReadCache, getAllKeys(state.riskTradeReadCache));
    }


    @Benchmark
    public void b04_GetTradeSettleCurrencyFilter(Blackhole blackhole, InitReadCacheState state) throws Exception
    {

        try (QueryCursor<Cache.Entry<Integer, RiskTrade>> cursor =
                     state.riskTradeReadCache.query(
                    new ScanQuery<Integer, RiskTrade>(new RiskTradeSettleCurrencyScanQuery("USD"))
                )
             )
        {
            cursor.forEach(entry -> state.riskTradeReadCache.get(entry.getKey()));
        }
    }

    @Benchmark
    public void b05_GetTradeThreeFilter(Blackhole blackhole, InitReadCacheState state) throws Exception
    {
        try (QueryCursor<Cache.Entry<Integer, RiskTrade>> cursor =
                     state.riskTradeReadCache.query(
                             new ScanQuery<Integer, RiskTrade>(
                         new RiskTradeThreeFieldScanQuery("USD", "traderName", "book"))
                         )
                     )
        {
            cursor.forEach(entry -> state.riskTradeReadCache.get(entry.getKey()));
        }

    }

    @Benchmark
    public void b06_GetTradeBookFilterHasIndex(Blackhole blackhole, InitReadCacheState state) throws Exception
    {
//        SqlQuery sql = new SqlQuery(RiskTrade.class, "book = '?';");

        final AtomicInteger counter = new AtomicInteger(0);

        RiskTradeBookScanQuery query = new RiskTradeBookScanQuery("book");

        try (QueryCursor<Cache.Entry<Integer, RiskTrade>> cursor = state.riskTradeReadCache.query(new ScanQuery<>(query)))
        {
            cursor.forEach(e ->
                    {
                        state.riskTradeReadCache.get(e.getKey());
                        counter.incrementAndGet();
                    }
                );
        }

        assert (counter.get() > 0);

    }


    // local runner for tests
    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
                .include(IgniteUseCasesBenchmark.class.getSimpleName())
                .warmupIterations(2)
                .measurementIterations(2)
                .forks(0)
                .jvmArgs("-ea")
                .shouldFailOnError(false) // switch to "true" to fail the complete run
                .build();

        new Runner(opt).run();
    }



}
