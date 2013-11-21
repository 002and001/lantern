package org.lantern;

import static org.junit.Assert.*;

import java.io.IOException;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

import org.junit.Test;
import org.lantern.TestUtils.TestModule;
import org.lantern.http.JettyLauncher;
import org.lantern.privacy.LocalCipherProvider;
import org.lantern.privacy.UnencryptedFileService;
import org.lantern.state.Model;
import org.lastbamboo.common.portmapping.NatPmpService;
import org.lastbamboo.common.portmapping.PortMapListener;
import org.lastbamboo.common.portmapping.PortMappingProtocol;
import org.lastbamboo.common.portmapping.UpnpService;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

public class LanternModuleTest {

    @Test
    public void test() throws Exception {
        final LanternModule lm = new LanternModule(new String[]{});
        
        lm.setLocalCipherProvider(new LocalCipherProvider() {

            @Override
            public Cipher newLocalCipher(int opmode) throws IOException,
                    GeneralSecurityException {
                return Cipher.getInstance("AES/CBC/PKCS5Padding");
            }

            @Override
            public boolean requiresAdditionalUserInput() {return false;}

            @Override
            public void feedUserInput(char[] input, boolean init)
                    throws IOException, GeneralSecurityException {}
            @Override
            public boolean isInitialized() {return true;}
            @Override
            public void reset() throws IOException {}
            
        });
        lm.setEncryptedFileService(new UnencryptedFileService());
        lm.setUpnpService(new UpnpService() {
            @Override
            public void shutdown() {}
            
            @Override
            public void removeUpnpMapping(int mappingIndex) {}
            
            @Override
            public int addUpnpMapping(PortMappingProtocol protocol, int localPort,
                    int externalPortRequested, PortMapListener portMapListener) {
                return 0;
            }
        });
        lm.setNatPmpService(new NatPmpService() {
            
            @Override
            public void shutdown() {}
            
            @Override
            public void removeNatPmpMapping(int mappingIndex) {}
            
            @Override
            public int addNatPmpMapping(PortMappingProtocol protocol, int localPort,
                    int externalPortRequested, PortMapListener portMapListener) {
                return 0;
            }
        });

        Module m = Modules.override(lm).with(new TestModule());
        final Injector injector = Guice.createInjector(m);

        final LanternService xmpp =
            injector.getInstance(DefaultXmppHandler.class);
        
        final Model model = injector.getInstance(Model.class);
        assertNotNull(xmpp);
        assertNotNull(model);
        assertTrue(model == injector.getInstance(Model.class));
        
        
        final LanternService jetty = 
                injector.getInstance(JettyLauncher.class);
        assertNotNull(jetty);
    }

}
