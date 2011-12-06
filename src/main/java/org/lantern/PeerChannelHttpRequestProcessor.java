package org.lantern;

import java.io.IOException;
import java.net.Socket;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;

import org.littleshoot.proxy.ProxyUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP request processor that sends requests to peers and 
 * writes HttpResponses to the browerToProxy channel. 
 * 
 * this differs from PeerHttpRequestProcessor in that it
 * wraps the littleshoot peer socket in a netty Channel with 
 * a pipeline that decodes the HttpResponse rather than 
 * relaying raw bytes.  We do this so that we can observe
 * characteristics of the response in the main browserToProxy
 * channel pipeline, eg observing Set-Cookie responses.
 *
 */
public class PeerChannelHttpRequestProcessor implements HttpRequestProcessor {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private volatile boolean startedCopying;
    private final Socket sock;
    private volatile PeerSocketChannel peerChannel;
    private volatile PeerSink peerSink;

    public PeerChannelHttpRequestProcessor(final Socket sock) {
        this.sock = sock;
        peerSink = new PeerSink();
    }

    @Override
    public boolean processRequest(final Channel browserToProxyChannel,
        final ChannelHandlerContext ctx, final MessageEvent me) 
        throws IOException {
        if (!startedCopying) {
            ChannelPipeline pipeline = Channels.pipeline();
            pipeline.addLast("decoder", new HttpResponseDecoder());
            pipeline.addLast("encoder", new HttpRequestEncoder());
            pipeline.addLast("relay", new RelayToBrowserHandler(browserToProxyChannel));

            peerChannel = new PeerSocketChannel(pipeline, peerSink, sock);
            peerChannel.simulateConnect();
            startedCopying = true;
        }

        final HttpRequest request = (HttpRequest) me.getMessage();
        Channels.write(peerChannel, request);
        
        // We return true in all these case to preserve the behavior before
        // the change to return a boolean. The point of returning a boolean
        // was more to consolidate the check for the existence of a proxy with
        // the request processing.
        return true;
    }

    @Override
    public boolean processChunk(final ChannelHandlerContext ctx, 
        final MessageEvent me) throws IOException {
        final HttpChunk chunk = (HttpChunk) me.getMessage();
        Channels.write(peerChannel, chunk);
        return true;
    }

    @Override
    public void close() {
        ProxyUtils.closeOnFlush(peerChannel);
    }

    // this is similar to OutboundHandler, unclear if we need similar complexity
    // here for range requests, the old version relayed raw bytes, so this 
    // seems sufficient.
    private class RelayToBrowserHandler extends SimpleChannelUpstreamHandler {
        
        Channel browserToProxyChannel;
        
        public RelayToBrowserHandler(Channel browserToProxyChannel) {
            this.browserToProxyChannel = browserToProxyChannel;
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
            browserToProxyChannel.write(me.getMessage());
        }
        
        @Override
         public void channelClosed(final ChannelHandlerContext ctx, 
             final ChannelStateEvent e) throws Exception {
             log.info("Channel to peer proxy closed, closing browserToProxy channel.");
             ProxyUtils.closeOnFlush(browserToProxyChannel);
         }

         @Override
         public void exceptionCaught(final ChannelHandlerContext ctx, 
             final ExceptionEvent e) throws Exception {
             log.error("Caught exception on peer proxy channel", e.getCause());
             Channels.close(e.getChannel()); 
         }
    }
}
