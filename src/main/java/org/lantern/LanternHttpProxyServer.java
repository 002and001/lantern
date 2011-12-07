package org.lantern;

import static org.jboss.netty.channel.Channels.pipeline;

import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.lantern.cookie.CookieFilter;
import org.lantern.cookie.SetCookieObserver;
import org.lantern.cookie.SetCookieObserverHandler;
import org.lantern.cookie.UpstreamCookieFilterHandler;
import org.littleshoot.commom.xmpp.XmppP2PClient;
import org.littleshoot.proxy.KeyStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP proxy server for local requests from the browser to Lantern.
 */
public class LanternHttpProxyServer implements HttpProxyServer {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final ChannelGroup allChannels = 
        new DefaultChannelGroup("Local-HTTP-Proxy-Server");
            
    private final int httpLocalPort;

    private final KeyStoreManager keyStoreManager;

    private final SetCookieObserver setCookieObserver;
    private final CookieFilter.Factory cookieFilterFactory;

    private final int httpsLocalPort;

    private final ProxyProvider proxyProvider;
    private final ProxyStatusListener proxyStatusListener;
    private final XmppP2PClient p2pClient;


    /**
     * Creates a new proxy server.
     * 
     * @param httpLocalPort The port the HTTP server should run on.
     * @param httpsLocalPort The port the HTTPS server should run on.
     * @param filters HTTP filters to apply.
     * @param xmpp The class dealing with all XMPP interaction with the server.
     */
    public LanternHttpProxyServer(final int httpLocalPort, 
        final int httpsLocalPort, final KeyStoreManager keyStoreManager, 
        final XmppHandler xmpp, SetCookieObserver setCookieObserver,
        CookieFilter.Factory cookieFilterFactory) {
        
        this(httpLocalPort, httpsLocalPort, keyStoreManager, xmpp, xmpp, xmpp.getP2PClient(),
             setCookieObserver, cookieFilterFactory);
    }

    public LanternHttpProxyServer(final int httpLocalPort, 
        final int httpsLocalPort, final KeyStoreManager keyStoreManager, 
        final ProxyProvider proxyProvider,
        final ProxyStatusListener proxyStatusListener, 
        final XmppP2PClient p2pClient, 
        SetCookieObserver setCookieObserver, 
        CookieFilter.Factory cookieFilterFactory) {
            
        this.httpLocalPort = httpLocalPort;
        this.httpsLocalPort = httpsLocalPort;
        this.proxyProvider = proxyProvider;
        this.proxyStatusListener = proxyStatusListener;
        this.p2pClient = p2pClient;
        this.keyStoreManager = keyStoreManager;
        this.setCookieObserver = setCookieObserver;
        this.cookieFilterFactory = cookieFilterFactory;

        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread t, final Throwable e) {
                log.error("Uncaught exception", e);
            }
        });    
    }


    @Override
    public void start() {
        log.info("Starting proxy on HTTP port "+httpLocalPort+
            " and HTTPS port "+httpsLocalPort);
        
        newServerBootstrap(newHttpChannelPipelineFactory(), 
            httpLocalPort);
        log.info("Built HTTP server");
        
        /*
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                log.info("Got shutdown hook...closing all channels.");
                final ChannelGroupFuture future = allChannels.close();
                try {
                    future.await(6*1000);
                } catch (final InterruptedException e) {
                    log.info("Interrupted", e);
                }
                bootstrap.releaseExternalResources();
                log.info("Closed all channels...");
            }
        }));
        */
    }
    
    private ServerBootstrap newServerBootstrap(
        final ChannelPipelineFactory pipelineFactory, final int port) {
        final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(new ThreadFactory() {
                    @Override
                    public Thread newThread(final Runnable r) {
                        final Thread t = 
                            new Thread(r, "Daemon-Netty-Boss-Executor");
                        t.setDaemon(true);
                        return t;
                    }
                }),
                Executors.newCachedThreadPool(new ThreadFactory() {
                    @Override
                    public Thread newThread(final Runnable r) {
                        final Thread t = 
                            new Thread(r, "Daemon-Netty-Worker-Executor");
                        t.setDaemon(true);
                        return t;
                    }
                })));

        bootstrap.setPipelineFactory(pipelineFactory);
        
        // We always only bind to localhost here for better security.
        final Channel channel = 
            bootstrap.bind(new InetSocketAddress("127.0.0.1", port));
        allChannels.add(channel);
        
        return bootstrap;
    }

    private ChannelPipelineFactory newHttpChannelPipelineFactory() {
        return new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                
                final SimpleChannelUpstreamHandler dispatcher = 
                    new DispatchingProxyRelayHandler(proxyProvider, 
                                                     proxyStatusListener, 
                                                     p2pClient, 
                                                     keyStoreManager);
                                                     
                
                final ChannelPipeline pipeline = pipeline();
                pipeline.addLast("decoder", 
                    new HttpRequestDecoder(8192, 8192*2, 8192*2));
                pipeline.addLast("encoder", 
                    new LanternHttpResponseEncoder(LanternHub.statsTracker()));
                
                if (setCookieObserver != null) {
                    final ChannelHandler watchCookies = new SetCookieObserverHandler(setCookieObserver);
                    pipeline.addLast("setCookieObserver", watchCookies);
                }
                
                if (cookieFilterFactory != null) {
                    final ChannelHandler filterCookies = new UpstreamCookieFilterHandler(cookieFilterFactory);
                    pipeline.addLast("cookieFilter", filterCookies);
                }

                pipeline.addLast("handler", dispatcher);

                return pipeline;
            }
        };

    }


}
