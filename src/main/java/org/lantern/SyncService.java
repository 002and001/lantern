package org.lantern;

import java.util.Timer;
import java.util.TimerTask;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.Configure;
import org.cometd.java.annotation.Listener;
import org.cometd.java.annotation.Service;
import org.cometd.java.annotation.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.Subscribe;

/**
 * Service for pushing updated Lantern state to the client.
 */
@Service("sync")
public class SyncService {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Session
    private ServerSession session;
    
    private volatile long lastUpdateTime = System.currentTimeMillis();
    
    /**
     * Creates a new sync service.
     */
    public SyncService() {
        // Make sure the config class is added as a listener before this class.
        LanternHub.register(this);
        
        final Timer timer = LanternHub.timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                sync();
            }
        }, 3000, 4000);
    }
    
    @SuppressWarnings("unused")
    @Configure("/service/sync")
    private void configureSync(final ConfigurableServerChannel channel) {
        channel.setPersistent(true);
    }
    
    @Listener(Channel.META_CONNECT)
    public void metaConnect(final ServerSession remote, final Message connect) {
        // Make sure we give clients the most recent data whenever they connect.
        log.debug("Got connection from client...syncing");
        sync();
    }

    @Listener("/service/sync")
    public void processSync(final ServerSession remote, final Message message) {
        log.debug("JSON: {}", message.getJSON());
        log.debug("DATA: {}", message.getData());
        log.debug("DATA CLASS: {}", message.getData().getClass());
        
        /*
        final String fullJson = message.getJSON();
        final String json = StringUtils.substringBetween(fullJson, "\"data\":", ",\"channel\":");
        final Map<String, Object> update = message.getDataAsMap();
        log.debug("MAP: {}", update);
        */

        log.debug("Pushing updated config to browser...");
        sync();
    }
    
    @Subscribe
    public void onUpdate(final UpdateEvent updateEvent) {
        log.debug("Got update");
        sync();
    }
    
    @Subscribe
    public void onSync(final SyncEvent syncEvent) {
        log.debug("Got sync event");
        // We want to force a sync here regardless of whether or not we've 
        // recently synced.
        sync(true);
    }
    
    @Subscribe
    public void onPresence(final PresenceEvent event) {
        log.debug("Got presence");
        sync();
    }

    @Subscribe
    public void removePresence(final RemovePresenceEvent event) {
        log.debug("Presence removed...");
        sync();
    }
    
    @Subscribe 
    public void onRosterStateChanged(final RosterStateChangedEvent rsce) {
        log.debug("Roster changed...");
        sync();
    }
    
    private void sync() {
        sync(false);
    }
    private void sync(final boolean force) {
        log.debug("Syncing with channel...");
        if (session == null) {
            log.debug("No session...not syncing");
            return;
        }
        final long elapsed = System.currentTimeMillis() - lastUpdateTime;
        if (!force && elapsed < 100) {
            log.debug("Not pushing more than 10 times a second...{} ms elapsed", 
                elapsed);
            return;
        }
        log.debug("Actually syncing...");
        final ClientSessionChannel channel = 
            session.getLocalSession().getChannel("/sync");
        
        if (channel != null) {
            final Settings settings = LanternHub.settings();
            channel.publish(settings);
            lastUpdateTime = System.currentTimeMillis();
        }
    }
}
