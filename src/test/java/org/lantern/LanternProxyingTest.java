package org.lantern;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Injector;

/**
 * End-to-end proxying test to make sure we're able to proxy access to
 * different sites.
 */
@Ignore
public class LanternProxyingTest {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Test
    public void testWithHttpClient() throws Exception {
        //System.setProperty("javax.net.debug", "ssl");
        //Launcher.main(false, args);
        
        final String[] args = new String[]{"--disable-ui", "--force-get", 
                "--refresh-tok", TestUtils.getRefreshToken(), 
                "--access-tok", TestUtils.getAccessToken(), 
                "--disable-trusted-peers", "--disable-anon-peers"};

        final LanternModule lm = new LanternModule(args);

        final Launcher launcher = new Launcher(lm);
        launcher.configureDefaultLogger();
        launcher.launch();
        launcher.model.setSetupComplete(true);
        
        final Injector injector = launcher.getInjector();
        final ProxyTracker proxyTracker = injector.getInstance(ProxyTracker.class);
        
        int tries = 0;
        while (!proxyTracker.hasProxy() && tries < 200) {
            Thread.sleep(200);
            tries++;
        }
        
        //LanternUtils.addCert("digicerthighassurancerootca", new File("certs/DigiCertHighAssuranceCA-3.cer"), certsFile, "changeit");
        //LanternUtils.addCert("littleproxy", new File("certs/littleproxy.cer"), certsFile, "changeit");
        //LanternUtils.addCert("equifaxsecureca", new File("certs/equifaxsecureca.cer"), certsFile, "changeit");
        
        final File certsFile = new File("src/test/resources/cacerts");
        if (!certsFile.isFile()) {
            throw new IllegalStateException("COULD NOT FIND CACERTS!!");
        }
        
        // We set this back to the global trust store because in this case 
        // we're testing a bunch of sites, not just the ones lantern accesses
        // internally.
        System.setProperty("javax.net.ssl.trustStore", certsFile.getCanonicalPath());
        
        LanternUtils.waitForServer(LanternConstants.LANTERN_LOCALHOST_HTTP_PORT);

        //final Collection<String> censored = Arrays.asList("myspace.com");
        //final Collection<String> censored = Arrays.asList("getlantern.org");
        //final Collection<String> censored = Arrays.asList("Roozonline.com");
        //final Collection<String> censored = Arrays.asList("appledaily.com.tw");
        //final Collection<String> censored = new HashSet<String>();
        
        final Collection<String> censored = Arrays.asList(//"exceptional.io");
            //"www.getlantern.org",
            "github.com",
            "facebook.com", 
            "appledaily.com.tw", 
            "orkut.com", 
            "voanews.com",
            "balatarin.com",
            "igfw.net" 
                );
        
        //final SSLSocketFactory socketFactory = 
            //new SSLSocketFactory(
              //  (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(),
                //new BrowserCompatHostnameVerifier());
        final HttpClient client = new DefaultHttpClient();
        //final Scheme sch = new Scheme("https", 443, socketFactory);
        //client.getConnectionManager().getSchemeRegistry().register(sch);
        
        final Collection<String> successful = new HashSet<String>();
        final Collection<String> failed = new HashSet<String>();
        for (final String site : censored) {
            log.debug("TESTING SITE: {}", site);
            final boolean succeeded = testWhitelistedSite(site, client,
                LanternConstants.LANTERN_LOCALHOST_HTTP_PORT);
            if (succeeded) {
                successful.add(site);
            } else {
                failed.add(site);
            }
        }
        assertEquals("There were site failures: "+failed, censored.size(), successful.size());
        //log.info("Stopping proxy");
        //proxy.stop();
        //Launcher.stop();
    }

    private boolean testWhitelistedSite(final String url, final HttpClient client,
        final int proxyPort) throws Exception {
        final HttpGet get = new HttpGet("http://"+url);
        
        // Some sites require more standard headers to be present.
        get.setHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.7; rv:15.0) Gecko/20100101 Firefox/15.0");
        get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        get.setHeader("Accept-Language", "en-us,en;q=0.5");
        get.setHeader("Accept-Encoding", "gzip, deflate");
        
        client.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 
            6000);
        // Timeout when server does not send data.
        client.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 30000);
        client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, 
            new HttpHost("localhost", proxyPort));
        final HttpResponse response;
        try {
            response = client.execute(get);
        } catch (final ClientProtocolException e) {
            log.warn("Protocol error connecting to "+url, e);
            throw e;
        } catch (final IOException e) {
            log.warn("IO error connecting to "+url, e);
            return false;
        } 
        
        if (200 !=  response.getStatusLine().getStatusCode()) {
            return false;
        }

        log.debug("Consuming entity");
        final HttpEntity entity = response.getEntity();
        final String raw = IOUtils.toString(entity.getContent());
        //log.debug("Raw response: "+raw);
        
        // The response body can actually be pretty small -- consider 
        // responses like 
        // <meta http-equiv="refresh" content="0;url=index.html">
        if (raw.length() <= 40) {
            return false;
        }
        EntityUtils.consume(entity);
        get.reset();
        return true;
    }
}
