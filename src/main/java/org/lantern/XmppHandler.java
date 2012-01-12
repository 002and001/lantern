package org.lantern;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.lastbamboo.common.p2p.P2PConstants;
import org.lastbamboo.common.portmapping.NatPmpService;
import org.lastbamboo.common.portmapping.PortMapListener;
import org.lastbamboo.common.portmapping.PortMappingProtocol;
import org.lastbamboo.common.portmapping.UpnpService;
import org.lastbamboo.common.stun.client.StunServerRepository;
import org.littleshoot.commom.xmpp.XmppP2PClient;
import org.littleshoot.commom.xmpp.XmppUtils;
import org.littleshoot.p2p.P2P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hoodcomputing.natpmp.NatPmpException;

/**
 * Handles logging in to the XMPP server and processing trusted users through
 * the roster.
 */
public class XmppHandler implements ProxyStatusListener, ProxyProvider {
    
    private static final Logger LOG = 
        LoggerFactory.getLogger(XmppHandler.class);
    
    /**
     * These are the centralized proxies this Lantern instance is using.
     */
    private final Set<ProxyHolder> proxySet =
        new HashSet<ProxyHolder>();
    private final Queue<ProxyHolder> proxies = 
        new ConcurrentLinkedQueue<ProxyHolder>();
    
    /**
     * This is the set of all peer proxies we know about. We may have 
     * established connections with some of them. The main purpose of this is
     * to avoid exchanging keys multiple times.
     */
    private final Set<URI> peerProxySet = new HashSet<URI>();
    
    private final Set<ProxyHolder> laeProxySet =
        new HashSet<ProxyHolder>();
    private final Queue<ProxyHolder> laeProxies = 
        new ConcurrentLinkedQueue<ProxyHolder>();

    private XmppP2PClient client;
    private boolean displayedUpdateMessage = false;
    
    private static final String UNCENSORED_ID = "-lan-";
    
    static {
        SmackConfiguration.setPacketReplyTimeout(30 * 1000);
    }

    private final int sslProxyRandomPort;

    private final Timer updateTimer = new Timer(true);

    private volatile long lastInfoMessageScheduled = 0L;
    
    private final MessageListener typedListener = new MessageListener() {

        @Override
        public void processMessage(final Chat ch, final Message msg) {
            // Note the Chat will always be null here. We try to avoid using
            // actual Chat instances due to Smack's strange and inconsistent
            // behavior with message listeners on chats.
            final String part = msg.getFrom();
            LOG.info("Got chat participant: {} with message:\n {}", part, 
                msg.toXML());
            if (StringUtils.isNotBlank(part) && 
                part.startsWith(LanternConstants.LANTERN_JID)) {
                processLanternHubMessage(msg);
            }

            final Integer type = 
                (Integer) msg.getProperty(P2PConstants.MESSAGE_TYPE);
            if (type != null) {
                LOG.info("Not processing typed message");
                processTypedMessage(msg, type);
            } 
        }

    };

    private String lastJson = "";

    private final int plainTextProxyRandomPort;

    /**
     * Creates a new XMPP handler.
     * 
     * @param keyStoreManager The class for managing certificates.
     * @param sslProxyRandomPort The port of the HTTP proxy that other peers  
     * will relay to.
     * @param plainTextProxyRandomPort The port of the HTTP proxy running
     * only locally and accepting plain-text sockets.
     */
    public XmppHandler(final int sslProxyRandomPort, 
        final int plainTextProxyRandomPort) {
        this.sslProxyRandomPort = sslProxyRandomPort;
        this.plainTextProxyRandomPort = plainTextProxyRandomPort;
        if (LanternHub.settings().isConnectOnLaunch() && 
            LanternUtils.isConfigured()) {
            try {
                connect();
            } catch (final IOException e) {
                LOG.info("Could not login", e);
            }
        }
    }
    
    public void connect() throws IOException {
        String email = LanternHub.settings().getEmail();
        String pwd = LanternHub.settings().getPassword();
        if (StringUtils.isBlank(email)) {
            if (!LanternUtils.runWithUi()) {
                email = askForEmail();
                LanternHub.settings().setEmail(email);
            } else {
                LOG.error("No user name");
                throw new IllegalStateException("No user name");
            }
        }
        
        if (StringUtils.isBlank(pwd)) {
            if (!LanternUtils.runWithUi()) {
                pwd = askForPassword();
                LanternHub.settings().setPassword(pwd);
            } else {
                LOG.error("No password.");
                throw new IllegalStateException("No password");
            }
        }
        
        LanternHub.settingsIo().write();
        
        final InetSocketAddress plainTextProxyRelayAddress = 
            new InetSocketAddress("127.0.0.1", plainTextProxyRandomPort);
        
        NatPmpService natPmpService = null;
        try {
            natPmpService = new NatPmp();
        } catch (final NatPmpException e) {
            LOG.error("Could not map", e);
            // We just use a dummy one in this case.
            natPmpService = new NatPmpService() {
                @Override
                public void removeNatPmpMapping(int arg0) {
                }
                @Override
                public int addNatPmpMapping(
                    final PortMappingProtocol arg0, int arg1, int arg2,
                    PortMapListener arg3) {
                    return -1;
                }
            };
        }
        
        final UpnpService upnpService = new Upnp();
        this.client = P2P.newXmppP2PHttpClient("shoot", natPmpService, 
            upnpService, new InetSocketAddress(this.sslProxyRandomPort), 
            //newTlsSocketFactory(), SSLServerSocketFactory.getDefault(),//newTlsServerSocketFactory(),
            newTlsSocketFactory(), newTlsServerSocketFactory(),
            //SocketFactory.getDefault(), ServerSocketFactory.getDefault(), 
            plainTextProxyRelayAddress, false);

        // This is a global, backup listener added to the client. We might
        // get notifications of messages twice in some cases, but that's
        // better than the alternative of sometimes not being notified
        // at all.
        LOG.info("Adding message listener...");
        this.client.addMessageListener(typedListener);
        LanternHub.eventBus().post(
            new ConnectivityStatusChangeEvent(ConnectivityStatus.CONNECTING));
        LanternHub.eventBus().post(
            new AuthenticationStatusEvent(AuthenticationStatus.LOGGING_IN));
        final String id;
        if (LanternHub.settings().isGetMode()) {
            id = "gmail.";
        } else {
            id = UNCENSORED_ID;
        }
        try {
            this.client.login(email, pwd, id);
        } catch (final IOException e) {
            LanternHub.eventBus().post(
                new ConnectivityStatusChangeEvent(ConnectivityStatus.DISCONNECTED));
            LanternHub.eventBus().post(
                new AuthenticationStatusEvent(AuthenticationStatus.LOGGED_OUT));
            throw e;
        }
        LanternHub.eventBus().post(
            new AuthenticationStatusEvent(AuthenticationStatus.LOGGED_IN));
        
        // We don't consider ourselves connected until we actually get
        // proxies to work with.
        //LanternHub.eventBus().post(
        //    new ConnectivityStatusChangeEvent(ConnectivityStatus.CONNECTED));
        final XMPPConnection connection = this.client.getXmppConnection();
        final Collection<InetSocketAddress> googleStunServers = 
            XmppUtils.googleStunServers(connection);
        StunServerRepository.setStunServers(googleStunServers);
        
        // Make sure all connections between us and the server are stored
        // OTR.
        LanternUtils.activateOtr(connection);
        
        LOG.info("Connection ID: {}", connection.getConnectionID());
        LOG.info("User: {}", connection.getUser());
        
        // Here we handle allowing the server to subscribe to our presence.
        connection.addPacketListener(new PacketListener() {
            
            @Override
            public void processPacket(final Packet pack) {
                LOG.info("Responding to subscribtion request from {} and to {}", 
                    pack.getFrom(), pack.getTo());
                final Presence packet = 
                    new Presence(Presence.Type.subscribed);
                packet.setTo(pack.getFrom());
                packet.setFrom(pack.getTo());
                connection.sendPacket(packet);
            }
        }, new PacketFilter() {
            
            @Override
            public boolean accept(final Packet packet) {
                //LOG.info("Filtering incoming packet:\n{}", packet.toXML());
                if(packet instanceof Presence) {
                    final Presence pres = (Presence) packet;
                    if(pres.getType().equals(Presence.Type.subscribe)) {
                        LOG.info("Got subscribe packet!!");
                        final String from = pres.getFrom();
                        if (from.startsWith("lantern-controller@") &&
                            from.endsWith("lantern-controller.appspotchat.com")) {
                            LOG.info("Got lantern subscription request!!");
                            return true;
                        } else {
                            LOG.info("Ignoring subscription request from: {}",
                                from);
                        }
                        
                    }
                } else {
                    LOG.info("Filtered out packet: ", packet.toXML());
                    //XmppUtils.printMessage(packet);
                }
                return false;
            }
        });
        
        gTalkSharedStatus();
        updatePresence();
        //sendInfoRequest();
        configureRoster();
    }
    

    public void disconnect() {
        LOG.info("Disconnecting!!");
        LanternHub.eventBus().post(
            new ConnectivityStatusChangeEvent(ConnectivityStatus.DISCONNECTING));
        LanternHub.eventBus().post(
            new AuthenticationStatusEvent(AuthenticationStatus.LOGGING_OUT));
        
        if (this.client != null) {
            this.client.logout();
        }
        
        LanternHub.eventBus().post(
            new ConnectivityStatusChangeEvent(ConnectivityStatus.DISCONNECTED));
        LanternHub.eventBus().post(
            new AuthenticationStatusEvent(AuthenticationStatus.LOGGED_OUT));
        proxySet.clear();
        proxies.clear();
        peerProxySet.clear();
        laeProxySet.clear();
        laeProxies.clear();
    }

    private void processLanternHubMessage(final Message msg) {

        LOG.info("Lantern controlling agent response");
        final String body = msg.getBody();
        LOG.info("Body: {}", body);
        final Object obj = JSONValue.parse(body);
        final JSONObject json = (JSONObject) obj;
        final JSONArray servers = 
            (JSONArray) json.get(LanternConstants.SERVERS);
        final Long delay = 
            (Long) json.get(LanternConstants.UPDATE_TIME);
        if (delay != null) {
            final long now = System.currentTimeMillis();
            final long elapsed = now - lastInfoMessageScheduled;
            if (elapsed < 10000) {
                LOG.info("Ignoring duplicate info request scheduling- "+
                    "scheduled request {} milliseconds ago.", elapsed);
                return;
            }
            lastInfoMessageScheduled = now;
            updateTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    updatePresence();
                }
            }, delay);
            LOG.info("Scheduled next info request in {} milliseconds", 
                delay);
        }
        
        if (servers == null) {
            LOG.info("XMPP: "+XmppUtils.toString(msg));
        } else {
            final Iterator<String> iter = servers.iterator();
            while (iter.hasNext()) {
                final String server = iter.next();
                addProxy(server);
                LanternUtils.addProxy(server);
            }
            if (!servers.isEmpty()) { 
                if (!Configurator.configured()) {
                    Configurator.configure();
                }
                LOG.info("Dispatching CONNECTED event");
                LanternHub.eventBus().post(new ConnectivityStatusChangeEvent(
                    ConnectivityStatus.CONNECTED));
            }
        }

        // This is really a JSONObject, but that itself is a map.
        final Map<String, String> update = 
            (Map<String, String>) json.get(LanternConstants.UPDATE_KEY);
        LOG.info("Already displayed update? {}", displayedUpdateMessage);
        if (update != null && !displayedUpdateMessage) {
            LOG.info("About to show update...");
            displayedUpdateMessage = true;
            LanternHub.display().asyncExec (new Runnable () {
                @Override
                public void run () {
                    LanternHub.eventBus().post(new UpdateEvent(update));
                }
            });
        }
    }

    private void gTalkSharedStatus() {
        // This is for Google Talk compatibility. Surprising, all we need to
        // do is grab our Google Talk shared status, signifying support for
        // their protocol, and then we don't interfere with GChat visibility.
        final Packet status = XmppUtils.getSharedStatus(
                this.client.getXmppConnection());
        LOG.info("Status:\n{}", status.toXML());
    }

    private String askForEmail() {
        try {
            System.out.print("Please enter your gmail e-mail, as in johndoe@gmail.com: ");
            return LanternUtils.readLineCLI();
        } catch (IOException e) {
            System.out.println("IO error trying to read your email address!");
            return "";
        }
    }
    
    private String askForPassword() {
        try {
            System.out.print("Please enter your gmail password: ");
            return new String(LanternUtils.readPasswordCLI());
        } catch (IOException e) {
            System.out.println("IO error trying to read your password!");
            return "";
        }
    }

    /**
     * Updates the user's presence. We also include any stats updates in this 
     * message. Note that periodic presence updates are also used on the server
     * side to verify which clients are actually available.
     * 
     * We in part send presence updates instead of typical chat messages to 
     * get around these messages showing up in the user's gchat window.
     */
    private void updatePresence() {
        final XMPPConnection conn = this.client.getXmppConnection();
        if (!conn.isConnected()) {
            LOG.info("Not updating presence when we're not connected");
            return;
        }
        LOG.info("Sending presence available");
        
        // OK, this is bizarre. For whatever reason, we **have** to send the
        // following packet in order to get presence events from our peers.
        // DO NOT REMOVE THIS MESSAGE!!
        final Presence pres = new Presence(Presence.Type.available);
        conn.sendPacket(pres);
        
        final Presence forHub = new Presence(Presence.Type.available);
        forHub.setTo(LanternConstants.LANTERN_JID);
        
        final JSONObject json = new JSONObject();
        final StatsTracker statsTracker = LanternHub.statsTracker();
        json.put(LanternConstants.COUNTRY_CODE, LanternHub.censored().countryCode());
        json.put(LanternConstants.BYTES_PROXIED, 
            statsTracker.getTotalBytesProxied());
        json.put(LanternConstants.DIRECT_BYTES, 
            statsTracker.getDirectBytes());
        json.put(LanternConstants.REQUESTS_PROXIED, 
            statsTracker.getTotalProxiedRequests());
        json.put(LanternConstants.DIRECT_REQUESTS, 
            statsTracker.getDirectRequests());
        //json.put(LanternConstants.WHITELIST_ADDITIONS, 
        //    LanternHub.whitelist().getAdditionsAsJson());
            //LanternUtils.toJsonArray(Whitelist.getAdditions()));
        //json.put(LanternConstants.WHITELIST_REMOVALS, 
        //    LanternHub.whitelist().getRemovalsAsJson());
        json.put(LanternConstants.VERSION_KEY, LanternConstants.VERSION);
        final String str = json.toJSONString();
        LOG.info("Reporting data: {}", str);
        if (!this.lastJson.equals(str)) {
            this.lastJson = str;
            forHub.setProperty("stats", str);
        } else {
            LOG.info("No new stats to report");
        }
        
        conn.sendPacket(forHub);
    }

    private void configureRoster() {
        final XMPPConnection xmpp = this.client.getXmppConnection();

        final Roster roster = xmpp.getRoster();

        final RosterEntry lantern = roster.getEntry(LanternConstants.LANTERN_JID);
        if (lantern == null) {
            LOG.info("Creating roster entry for Lantern...");
            //roster.createEntry(LANTERN_JID, "Lantern", null);
        }
        roster.setSubscriptionMode(Roster.SubscriptionMode.manual);
        
        roster.addRosterListener(new RosterListener() {
            @Override
            public void entriesDeleted(final Collection<String> addresses) {
                LOG.info("Entries deleted");
                for (final String address : addresses) {
                    LanternHub.eventBus().post(new RemovePresenceEvent(address));
                }
            }
            @Override
            public void entriesUpdated(final Collection<String> addresses) {
                LOG.info("Entries updated: {}", addresses);
            }
            @Override
            public void presenceChanged(final Presence presence) {
                //LOG.info("Processing presence changed: {}", presence);
                processPresence(presence);
            }
            @Override
            public void entriesAdded(final Collection<String> addresses) {
                LOG.info("Entries added: "+addresses);
                for (final String address : addresses) {
                    //presences.add(address);
                }
            }
        });
        
        // Now we add all the existing entries to get people who are already
        // online.
        final Collection<RosterEntry> entries = roster.getEntries();
        for (final RosterEntry entry : entries) {
            //xmpp.sendPacket(packet)
            final Iterator<Presence> presences = 
                roster.getPresences(entry.getUser());
            while (presences.hasNext()) {
                final Presence p = presences.next();
                processPresence(p);
            }
        }
        LOG.info("Finished adding listeners");
    }
    
    private void processPresence(final Presence presence) {
        final String from = presence.getFrom();
        LOG.info("Got presence: {}", presence.toXML());
        if (isLanternHub(from)) {
            LOG.info("Got Lantern hub presence");
        }
        else if (isLanternJid(from)) {
            addOrRemovePeer(presence, from);
            LanternHub.eventBus().post(
                new AddPresenceEvent(from, new LanternPresence(presence)));
        } else {
            LanternHub.eventBus().post(
                new AddPresenceEvent(from, new LanternPresence(presence)));
        }
    }

    /*
    private void sendInfoRequest() {
        // Send an "info" message to gather proxy data.
        LOG.info("Sending INFO request");
        final Message msg = new Message();
        msg.setType(Type.chat);
        //msg.setType(Type.normal);
        msg.setTo(LanternConstants.LANTERN_JID);
        msg.setFrom(this.client.getXmppConnection().getUser());
        final JSONObject json = new JSONObject();
        final StatsTracker statsTracker = LanternHub.statsTracker();
        json.put(LanternConstants.COUNTRY_CODE, CensoredUtils.countryCode());
        json.put(LanternConstants.BYTES_PROXIED, 
            statsTracker.getTotalBytesProxied());
        json.put(LanternConstants.DIRECT_BYTES, 
            statsTracker.getDirectBytes());
        json.put(LanternConstants.REQUESTS_PROXIED, 
            statsTracker.getTotalProxiedRequests());
        json.put(LanternConstants.DIRECT_REQUESTS, 
            statsTracker.getDirectRequests());
        json.put(LanternConstants.WHITELIST_ADDITIONS, 
            LanternUtils.toJsonArray(Whitelist.getAdditions()));
        json.put(LanternConstants.WHITELIST_REMOVALS, 
            LanternUtils.toJsonArray(Whitelist.getRemovals()));
        json.put(LanternConstants.VERSION_KEY, LanternConstants.VERSION);
        final String str = json.toJSONString();
        LOG.info("Reporting data: {}", str);
        msg.setBody(str);
        
        this.client.getXmppConnection().sendPacket(msg);
        Whitelist.whitelistReported();
        //statsTracker.clear();
    }
    */

    private void addOrRemovePeer(final Presence p, final String from) {
        LOG.info("Processing peer: {}", from);
        final URI uri;
        try {
            uri = new URI(from);
        } catch (final URISyntaxException e) {
            LOG.error("Could not create URI from: {}", from);
            return;
        }
        if (p.isAvailable()) {
            LOG.info("Processing available peer");
            // OK, we just request a certificate every time we get a present 
            // peer. If we get a response, this peer will be added to active
            // peer URIs.
            sendAndRequestCert(uri);
        }
        else {
            LOG.info("Removing JID for peer '"+from+"' with presence: {}", p);
            removePeer(uri);
        }
    }

    private boolean isLanternHub(final String from) {
        return from.startsWith("lantern-controller@") && 
            from.contains("lantern-controller.appspot");
    }

    private void sendErrorMessage(final InetSocketAddress isa,
        final String message) {
        final Message msg = new Message();
        msg.setProperty(P2PConstants.MESSAGE_TYPE, 
            XmppMessageConstants.ERROR_TYPE);
        final String errorMessage = "Error: "+message+" with host: "+isa;
        msg.setProperty(XmppMessageConstants.MESSAGE, errorMessage);
        this.client.getXmppConnection().sendPacket(msg);
    }
    
    private void processTypedMessage(final Message msg, final Integer type) {
        final String from = msg.getFrom();
        LOG.info("Processing typed message from {}", from);
        
        switch (type) {
            case (XmppMessageConstants.INFO_REQUEST_TYPE):
                LOG.info("Handling INFO request from {}", from);
                processInfoData(msg);
                sendInfoResponse(from);
                break;
            case (XmppMessageConstants.INFO_RESPONSE_TYPE):
                LOG.info("Handling INFO response from {}", from);
                processInfoData(msg);
                break;
            default:
                LOG.warn("Did not understand type: "+type);
                break;
        }
    }
    
    private void sendInfoResponse(final String from) {
        final Message msg = new Message();
        // The from becomes the to when we're responding.
        msg.setTo(from);
        msg.setProperty(P2PConstants.MESSAGE_TYPE, 
            XmppMessageConstants.INFO_RESPONSE_TYPE);
        msg.setProperty(P2PConstants.MAC, LanternUtils.getMacAddress());
        msg.setProperty(P2PConstants.CERT, 
            LanternHub.getKeyStoreManager().getBase64Cert());
        this.client.getXmppConnection().sendPacket(msg);
    }

    private void processInfoData(final Message msg) {
        LOG.info("Processing INFO data from request or response.");
        final URI uri;
        try {
            uri = new URI(msg.getFrom());
        } catch (final URISyntaxException e) {
            LOG.error("Could not create URI from: {}", msg.getFrom());
            return;
        }

        final String mac = (String) msg.getProperty(P2PConstants.MAC);
        final String base64Cert = (String) msg.getProperty(P2PConstants.CERT);
        LOG.info("Base 64 cert: {}", base64Cert);
        
        if (StringUtils.isNotBlank(base64Cert)) {
            LOG.info("Got certificate:\n"+
                new String(Base64.decodeBase64(base64Cert)));
            try {
                // Add the peer if we're able to add the cert.
                LanternHub.getKeyStoreManager().addBase64Cert(mac, base64Cert);
                if (LanternHub.getTrustedContactsManager().isTrusted(msg)) {
                    LanternHub.trustedPeerProxyManager().onPeer(uri);
                } else {
                    LanternHub.anonymousPeerProxyManager().onPeer(uri);
                }

                /*
                if (LanternHub..getTrustedContactsManager().isTrusted(msg)) {
                    this.establishedPeerProxies.add(uri);
                } else {
                    this.establishedAnonymousProxies.add(uri);
                }
                */
            } catch (final IOException e) {
                LOG.error("Could not add cert??", e);
            }
        } else {
            LOG.error("No cert for peer?");
        }
    }

    private void addProxy(final String cur) {
        LOG.info("Considering proxy: {}", cur);
        if (cur.contains("appspot")) {
            addLaeProxy(cur);
            return;
        }
        if (!this.client.getXmppConnection().isConnected()) {
            LOG.info("Not connected -- ignoring proxy: {}", cur);
            return;
        }
        final String jid = this.client.getXmppConnection().getUser().trim();
        final String emailId = XmppUtils.jidToUser(jid);
        LOG.info("We are: {}", jid);
        LOG.info("Service name: {}",
             this.client.getXmppConnection().getServiceName());
        if (jid.equals(cur.trim())) {
            LOG.info("Not adding ourselves as a proxy!!");
            return;
        }
        if (cur.startsWith(emailId+"/")) {
            try {
                addPeerProxy(new URI(cur));
            } catch (final URISyntaxException e) {
                LOG.error("Error with proxy URI", e);
            }
        } else if (cur.contains("@")) {
            try {
                addPeerProxy(new URI(cur));
            } catch (final URISyntaxException e) {
                LOG.error("Error with proxy URI", e);
            }
        } else {
            addGeneralProxy(cur);
        }
    }

    
    private void addPeerProxy(final URI peerUri) {
        LOG.info("Considering peer proxy");
        synchronized (peerProxySet) {
            // We purely do this to keep track of which peers we've attempted
            // to establish connections to.
            
            // TODO: I believe this excludes exchanging keys with peers who
            // are on multiple machines when the peer URI is a general JID and
            // not an instance JID.
            if (!peerProxySet.contains(peerUri)) {
                LOG.info("Actually adding peer proxy: {}", peerUri);
                peerProxySet.add(peerUri);
                sendAndRequestCert(peerUri);
            } else {
                LOG.info("We already know about the peer proxy");
            }
        }
    }
    
    private void sendAndRequestCert(final URI cur) {
        LOG.info("Requesting cert from {}", cur);
        final Message msg = new Message();
        msg.setProperty(P2PConstants.MESSAGE_TYPE, 
            XmppMessageConstants.INFO_REQUEST_TYPE);
        
        msg.setTo(cur.toASCIIString());
        // Set our certificate in the request as well -- we want to make
        // extra sure these get through!
        msg.setProperty(P2PConstants.MAC, LanternUtils.getMacAddress());
        msg.setProperty(P2PConstants.CERT, 
            LanternHub.getKeyStoreManager().getBase64Cert());
        this.client.getXmppConnection().sendPacket(msg);
    }

    private void addLaeProxy(final String cur) {
        LOG.info("Adding LAE proxy");
        addProxyWithChecks(this.laeProxySet, this.laeProxies, 
            new ProxyHolder(cur, new InetSocketAddress(cur, 443)));
    }
    
    private void addGeneralProxy(final String cur) {
        final String hostname = 
            StringUtils.substringBefore(cur, ":");
        final int port = 
            Integer.parseInt(StringUtils.substringAfter(cur, ":"));
        final InetSocketAddress isa = 
            new InetSocketAddress(hostname, port);
        addProxyWithChecks(proxySet, proxies, new ProxyHolder(hostname, isa));
    }

    private void addProxyWithChecks(final Set<ProxyHolder> set,
        final Queue<ProxyHolder> queue, final ProxyHolder ph) {
        if (set.contains(ph)) {
            LOG.info("We already know about proxy "+ph+" in {}", set);
            return;
        }
        
        final Socket sock = new Socket();
        try {
            sock.connect(ph.isa, 60*1000);
            synchronized (set) {
                if (!set.contains(ph)) {
                    set.add(ph);
                    queue.add(ph);
                    LOG.info("Queue is now: {}", queue);
                }
            }
        } catch (final IOException e) {
            LOG.error("Could not connect to: {}", ph);
            sendErrorMessage(ph.isa, e.getMessage());
            onCouldNotConnect(ph.isa);
        } finally {
            try {
                sock.close();
            } catch (final IOException e) {
                LOG.info("Exception closing", e);
            }
        }
    }

    protected boolean isLanternJid(final String from) {
        // Here's the format we're looking for: "-la-"
        if (from.contains("/"+UNCENSORED_ID)) {
            LOG.info("Returning Lantern TRUE for from: {}", from);
            return true;
        }
        return false;
    }

    
    @Override
    public void onCouldNotConnect(final InetSocketAddress proxyAddress) {
        // This can happen in several scenarios. First, it can happen if you've
        // actually disconnected from the internet. Second, it can happen if
        // the proxy is blocked. Third, it can happen when the proxy is simply
        // down for some reason.
        LOG.info("COULD NOT CONNECT TO STANDARD PROXY!! Proxy address: {}", 
            proxyAddress);
        
        // For now we assume this is because we've lost our connection.
        //onCouldNotConnect(new ProxyHolder(proxyAddress.getHostName(), proxyAddress), 
        //    this.proxySet, this.proxies);
    }
    
    @Override
    public void onCouldNotConnectToLae(final InetSocketAddress proxyAddress) {
        LOG.info("COULD NOT CONNECT TO LAE PROXY!! Proxy address: {}", 
            proxyAddress);
        
        // For now we assume this is because we've lost our connection.
        
        //onCouldNotConnect(new ProxyHolder(proxyAddress.getHostName(), proxyAddress), 
        //    this.laeProxySet, this.laeProxies);
    }
    
    private void onCouldNotConnect(final ProxyHolder proxyAddress,
        final Set<ProxyHolder> set, final Queue<ProxyHolder> queue){
        LOG.info("COULD NOT CONNECT!! Proxy address: {}", proxyAddress);
        synchronized (this.proxySet) {
            set.remove(proxyAddress);
            queue.remove(proxyAddress);
        }
    }

    @Override
    public void onCouldNotConnectToPeer(final URI peerUri) {
        removePeer(peerUri);
    }
    
    @Override
    public void onError(final URI peerUri) {
        removePeer(peerUri);
    }

    private void removePeer(final URI uri) {
        // We always remove from both since their trusted status could have
        // changed 
        removePeerUri(uri);
        removeAnonymousPeerUri(uri);
    }
    
    private void removePeerUri(final URI peerUri) {
        LOG.info("Removing peer with URI: {}", peerUri);
        //remove(peerUri, this.establishedPeerProxies);
    }

    private void removeAnonymousPeerUri(final URI peerUri) {
        LOG.info("Removing anonymous peer with URI: {}", peerUri);
        //remove(peerUri, this.establishedAnonymousProxies);
    }
    
    private void remove(final URI peerUri, final Queue<URI> queue) {
        LOG.info("Removing peer with URI: {}", peerUri);
        queue.remove(peerUri);
    }
    
    @Override
    public InetSocketAddress getLaeProxy() {
        return getProxy(this.laeProxies);
    }
    
    @Override
    public InetSocketAddress getProxy() {
        return getProxy(this.proxies);
    }
    
    @Override
    public PeerProxyManager getAnonymousPeerProxyManager() {
        return LanternHub.anonymousPeerProxyManager();
    }
    
    @Override
    public PeerProxyManager getTrustedPeerProxyManager() {
        return LanternHub.trustedPeerProxyManager();
    }
    
    
    /*
    @Override
    public URI getAnonymousProxy() {
        LOG.info("Getting anonymous proxy");
        return getProxyUri(this.establishedAnonymousProxies);
    }

    @Override
    public URI getPeerProxy() {
        LOG.info("Getting peer proxy");
        final URI proxy = getProxyUri(this.establishedPeerProxies);
        if (proxy == null) {
            LOG.info("Peer proxies {} and anonymous proxies {}", 
                this.establishedPeerProxies, this.establishedAnonymousProxies);
        }
        return proxy;
    }
    
    private URI getProxyUri(final Queue<URI> queue) {
        if (queue.isEmpty()) {
            LOG.info("No proxy URIs");
            return null;
        }
        final URI proxy = queue.remove();
        queue.add(proxy);
        LOG.info("FIFO queue is now: {}", queue);
        return proxy;
    }
    */

    private InetSocketAddress getProxy(final Queue<ProxyHolder> queue) {
        if (queue.isEmpty()) {
            LOG.info("No proxy addresses");
            return null;
        }
        final ProxyHolder proxy = queue.remove();
        queue.add(proxy);
        LOG.info("FIFO queue is now: {}", queue);
        return proxy.isa;
    }

    public XmppP2PClient getP2PClient() {
        return client;
    }

    private static final class ProxyHolder {
        
        private final String id;
        private final InetSocketAddress isa;

        private ProxyHolder(final String id, final InetSocketAddress isa) {
            this.id = id;
            this.isa = isa;
        }
        
        @Override
        public String toString() {
            return "ProxyHolder [isa=" + isa + "]";
        }
        
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((id == null) ? 0 : id.hashCode());
            result = prime * result + ((isa == null) ? 0 : isa.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ProxyHolder other = (ProxyHolder) obj;
            if (id == null) {
                if (other.id != null)
                    return false;
            } else if (!id.equals(other.id))
                return false;
            if (isa == null) {
                if (other.isa != null)
                    return false;
            } else if (!isa.equals(other.isa))
                return false;
            return true;
        }
    }
    
    private ServerSocketFactory newTlsServerSocketFactory() {
        LOG.info("Creating TLS server socket factory");
        String algorithm = 
            Security.getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }
        try {
            final KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(LanternHub.getKeyStoreManager().keyStoreAsInputStream(),
                    LanternHub.getKeyStoreManager().getKeyStorePassword());

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
            kmf.init(ks, LanternHub.getKeyStoreManager().getCertificatePassword());

            // Initialize the SSLContext to work with our key managers.
            final SSLContext serverContext = SSLContext.getInstance("TLS");
            serverContext.init(kmf.getKeyManagers(), null, null);
            return serverContext.getServerSocketFactory();
        } catch (final KeyStoreException e) {
            throw new Error("Could not create SSL server socket factory.", e);
        } catch (final NoSuchAlgorithmException e) {
            throw new Error("Could not create SSL server socket factory.", e);
        } catch (final CertificateException e) {
            throw new Error("Could not create SSL server socket factory.", e);
        } catch (final IOException e) {
            throw new Error("Could not create SSL server socket factory.", e);
        } catch (final UnrecoverableKeyException e) {
            throw new Error("Could not create SSL server socket factory.", e);
        } catch (final KeyManagementException e) {
            throw new Error("Could not create SSL server socket factory.", e);
        }
    }

    private SocketFactory newTlsSocketFactory() {
        LOG.info("Creating TLS socket factory");
        try {
            final SSLContext clientContext = SSLContext.getInstance("TLS");
            clientContext.init(null, 
                LanternHub.getKeyStoreManager().getTrustManagers(), null);
            return clientContext.getSocketFactory();
        } catch (final NoSuchAlgorithmException e) {
            LOG.error("No TLS?", e);
            throw new Error("No TLS?", e);
        } catch (final KeyManagementException e) {
            LOG.error("Key managmement issue?", e);
            throw new Error("Key managmement issue?", e);
        }
    }
}
