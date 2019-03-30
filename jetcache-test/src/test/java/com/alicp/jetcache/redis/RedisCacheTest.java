package com.alicp.jetcache.redis;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.LoadingCacheTest;
import com.alicp.jetcache.RefreshCacheTest;
import com.alicp.jetcache.support.*;
import com.alicp.jetcache.test.external.AbstractExternalCacheTest;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.Assert;
import org.junit.Test;
import redis.clients.jedis.*;
import redis.clients.util.Pool;
import redis.clients.util.ShardInfo;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created on 2016/10/8.
 *
 * @author <a href="mailto:areyouok@gmail.com">huangli</a>
 */
public class RedisCacheTest extends AbstractExternalCacheTest {

    @Test
    public void testSimplePool() throws Exception {
        System.out.println("RedisCacheTest.testSimplePool");
        GenericObjectPoolConfig pc = new GenericObjectPoolConfig();
        pc.setMinIdle(2);
        pc.setMaxIdle(10);
        pc.setMaxTotal(10);
        JedisPool pool = new JedisPool(pc, "localhost", 6379);

        testWithPool(pool, true);
    }

    @Test
    public void testSentinel() throws Exception {
        System.out.println("RedisCacheTest.testSentinel");
        GenericObjectPoolConfig pc = new GenericObjectPoolConfig();
        pc.setMinIdle(2);
        pc.setMaxIdle(10);
        pc.setMaxTotal(10);

        Set<String> sentinels = new HashSet<>();
        sentinels.add("127.0.0.1:26379");
        sentinels.add("127.0.0.1:26380");
        sentinels.add("127.0.0.1:26381");
        JedisSentinelPool pool = new JedisSentinelPool("mymaster", sentinels, pc);

        testWithPool(pool, true);
    }

    @Test
    public void shardTest() throws Exception {
        System.out.println("RedisCacheTest.shardTest");
        GenericObjectPoolConfig pc = new GenericObjectPoolConfig();
        pc.setMinIdle(2);
        pc.setMaxIdle(10);
        pc.setMaxTotal(10);

        List list = new ArrayList();
        ShardInfo si0 = new JedisShardInfo("localhost", 6379);
        ShardInfo si1 = new JedisShardInfo("localhost", 6379);
        list.add(si0);
        list.add(si1);

        ShardedJedisPool pool = new ShardedJedisPool(pc, list);

        // there is probably leak in ShardedJedisPool, so disable concurrent test temporarily
        testWithPool(pool, false);
    }

    private void testWithPool(Pool pool, boolean concurrentTest) throws Exception {
        cache = RedisCacheBuilder.createRedisCacheBuilder()
                .keyConvertor(FastjsonKeyConvertor.INSTANCE)
                .valueEncoder(JavaValueEncoder.INSTANCE)
                .valueDecoder(JavaValueDecoder.INSTANCE)
                .jedisPool(pool)
                .keyPrefix(new Random().nextInt() + "")
                .expireAfterWrite(500, TimeUnit.MILLISECONDS)
                .buildCache();

        Assert.assertSame(pool, cache.unwrap(Pool.class));
        if (pool instanceof JedisPool) {
            Assert.assertSame(pool, cache.unwrap(JedisPool.class));
        } else {
            Assert.assertSame(pool, cache.unwrap(JedisSentinelPool.class));
        }

        baseTest();
        fastjsonKeyCoverterTest();
        expireAfterWriteTest(cache.config().getExpireAfterWriteInMillis());

        LoadingCacheTest.loadingCacheTest(RedisCacheBuilder.createRedisCacheBuilder()
                .keyConvertor(FastjsonKeyConvertor.INSTANCE)
                .valueEncoder(JavaValueEncoder.INSTANCE)
                .valueDecoder(JavaValueDecoder.INSTANCE)
                .jedisPool(pool)
                .keyPrefix(new Random().nextInt() + ""), 0);
        RefreshCacheTest.refreshCacheTest(RedisCacheBuilder.createRedisCacheBuilder()
                .keyConvertor(FastjsonKeyConvertor.INSTANCE)
                .valueEncoder(JavaValueEncoder.INSTANCE)
                .valueDecoder(JavaValueDecoder.INSTANCE)
                .jedisPool(pool)
                .keyPrefix(new Random().nextInt() + ""), 200, 100);


        cache = RedisCacheBuilder.createRedisCacheBuilder()
                .keyConvertor(null)
                .valueEncoder(KryoValueEncoder.INSTANCE)
                .valueDecoder(KryoValueDecoder.INSTANCE)
                .jedisPool(pool)
                .keyPrefix(new Random().nextInt() + "")
                .buildCache();
        nullKeyConvertorTest();

        if (concurrentTest) {
            int thread = 10;
            int time = 3000;
            cache = RedisCacheBuilder.createRedisCacheBuilder()
                    .keyConvertor(FastjsonKeyConvertor.INSTANCE)
                    .valueEncoder(KryoValueEncoder.INSTANCE)
                    .valueDecoder(KryoValueDecoder.INSTANCE)
                    .jedisPool(pool)
                    .keyPrefix(new Random().nextInt() + "")
                    .buildCache();
            concurrentTest(thread, 500, time);
        }
    }

    @Test
    public void testRandomIndex() {
        {
            int[] ws = new int[]{100, 100, 100};
            int[] result = new int[3];
            for (int i = 0; i < 10000; i++) {
                int index = RedisCache.randomIndex(ws);
                result[index]++;
            }
            Assert.assertEquals(1.0, 1.0 * result[1] / result[0], 0.2);
            Assert.assertEquals(1.0, 1.0 * result[2] / result[0], 0.2);
        }
        {
            int[] ws = new int[]{1, 2, 3};
            int[] result = new int[3];
            for (int i = 0; i < 10000; i++) {
                int index = RedisCache.randomIndex(ws);
                result[index]++;
            }
            Assert.assertEquals(2.0, 1.0 * result[1] / result[0], 0.2);
            Assert.assertEquals(3.0, 1.0 * result[2] / result[0], 0.4);
        }
    }

    @Test
    public void readFromSlaveTest() throws Exception {
        System.out.println("RedisCacheTest.readFromSlaveTest");
        GenericObjectPoolConfig pc = new GenericObjectPoolConfig();
        pc.setMinIdle(2);
        pc.setMaxIdle(10);
        pc.setMaxTotal(10);
        JedisPool pool1 = new JedisPool(pc, "localhost", 6379);
        JedisPool pool2 = new JedisPool(pc, "localhost", 6380);
        JedisPool pool3 = new JedisPool(pc, "localhost", 6381);

        RedisCacheBuilder builder = RedisCacheBuilder.createRedisCacheBuilder();
        builder.setJedisPool(pool1);
        builder.setReadFromSlave(true);
        builder.setJedisSlavePools(pool2, pool3);
        builder.setSlaveReadWeights(1, 1);
        builder.setKeyConvertor(FastjsonKeyConvertor.INSTANCE);
        builder.setValueEncoder(JavaValueEncoder.INSTANCE);
        builder.setValueDecoder(JavaValueDecoder.INSTANCE);
        builder.setKeyPrefix(new Random().nextInt() + "");
        builder.setExpireAfterWriteInMillis(500);

        Cache cache = builder.buildCache();
        cache.put("readFromSlaveTest_K1", "V1");
        Assert.assertNotSame(pool1, ((RedisCache) cache).getReadPool());
        Assert.assertNotSame(pool1, ((RedisCache) cache).getReadPool());
        Assert.assertNotSame(pool1, ((RedisCache) cache).getReadPool());
        Assert.assertNotSame(pool1, ((RedisCache) cache).getReadPool());
        Thread.sleep(15);
        Assert.assertEquals("V1", cache.get("readFromSlaveTest_K1"));
        Assert.assertEquals("V1", cache.get("readFromSlaveTest_K1"));
        Assert.assertEquals("V1", cache.get("readFromSlaveTest_K1"));
        Assert.assertEquals("V1", cache.get("readFromSlaveTest_K1"));
    }
}
