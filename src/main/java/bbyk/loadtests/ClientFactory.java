package bbyk.loadtests;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import net.rubyeye.xmemcached.MemcachedClientBuilder;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.spy.memcached.ConnectionFactoryBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.couchbase.client.CouchbaseClient;
import com.couchbase.client.CouchbaseConnectionFactory;
import com.couchbase.client.CouchbaseConnectionFactoryBuilder;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.whalin.MemCached.MemCachedClient;
import com.whalin.MemCached.SockIOPool;

/**
 * @author bbyk
 */
public class ClientFactory
{
    private ClientSetup          setup;
    private InetSocketAddress[]  addresses;
    private BasicMemcachedClient sharedClient;

    public ClientFactory(@NotNull ClientSetup setup, @NotNull InetSocketAddress[] addresses)
    {
        this.setup = setup;
        this.addresses = addresses;

        SockIOPool pool = SockIOPool.getInstance();
        final String[] strAddresses = Iterables.toArray(Iterables.transform(Arrays.asList(addresses), new Function<InetSocketAddress, String>()
        {
            public String apply(InetSocketAddress address)
            {
                return address.getHostName() + ':' + address.getPort();
            }
        }), String.class);
        pool.setServers(strAddresses);
        pool.setSocketTO(2000); // whalin socket timeout
        pool.initialize();
    }

    public BasicMemcachedClient getOrCreate() throws Exception
    {
        if (setup.isShared)
        {
            if (sharedClient == null)
                sharedClient = create();
            return sharedClient;
        }
        else
        {
            return create();
        }
    }

    private BasicMemcachedClient create() throws Exception
    {

        switch (setup)
        {
        default:
        case SHARED_ONE_SPY_MEMCACHED:
        case PER_THREAD_SPY_MEMCACHED:
            final ConnectionFactoryBuilder spyBuilder = new ConnectionFactoryBuilder();
            spyBuilder.setTimeoutExceptionThreshold(2000);
            spyBuilder.setOpTimeout(2000); // 2 sec

            return new BasicMemcachedClient()
            {
                final net.spy.memcached.MemcachedClient c = new net.spy.memcached.MemcachedClient(spyBuilder.build(), Arrays.asList(addresses));

                public byte[] get(@NotNull String key)
                {
                    return (byte[]) c.get(key);
                }

                public void set(@NotNull String key, byte[] buffer)
                {
                    c.set(key, 0, buffer);
                }
            };
        case SHARED_ONE_WHALIN:
            return new BasicMemcachedClient()
            {
                final MemCachedClient c = new MemCachedClient();

                public byte[] get(@NotNull String key)
                {
                    return (byte[]) c.get(key);
                }

                public void set(@NotNull String key, @Nullable byte[] buffer)
                {
                    c.set(key, buffer);
                }
            };
        case SHARED_ONE_XMEMCACHED:
            final MemcachedClientBuilder xbuilder = new XMemcachedClientBuilder(Arrays.asList(addresses));
            xbuilder.setConnectionPoolSize(2);
            xbuilder.getConfiguration().setSoTimeout(2000); // 2 sec

            return new BasicMemcachedClient()
            {
                final net.rubyeye.xmemcached.MemcachedClient c = xbuilder.build();

                public byte[] get(@NotNull String key)
                {
                    try
                    {
                        return (byte[]) c.get(key);
                    }
                    catch (Exception e)
                    {
                        throw Throwables.propagate(e);
                    }
                }

                public void set(@NotNull String key, @Nullable byte[] buffer)
                {
                    try
                    {
                        c.set(key, 0, buffer);
                    }
                    catch (Exception e)
                    {
                        throw Throwables.propagate(e);
                    }
                }
            };

        case SHARED_ONE_COUCHBASE:
            final CouchbaseConnectionFactoryBuilder couchbaseBuilder = new CouchbaseConnectionFactoryBuilder();
            couchbaseBuilder.setTimeoutExceptionThreshold(2000);
            couchbaseBuilder.setOpTimeout(2000); // 2 sec

            // TODO: This is a gimpy hard-coded hack!!!
            List<URI> couchbaseUris = Lists.newArrayList(new URI("http://localhost:8091/pools"));

            final CouchbaseConnectionFactory connectionFactory = couchbaseBuilder.buildCouchbaseConnection(couchbaseUris, "default", "", "");

            return new BasicMemcachedClient()
            {
                final CouchbaseClient c = new CouchbaseClient(connectionFactory);

                public byte[] get(@NotNull String key)
                {
                    return (byte[]) c.get(key);
                }

                public void set(@NotNull String key, byte[] buffer)
                {
                    c.set(key, 0, buffer);
                }
            };
        }
    }
}
