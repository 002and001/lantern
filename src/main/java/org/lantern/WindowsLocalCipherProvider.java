package org.lantern; 

import com.google.common.io.Files;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream; 
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import net.sourceforge.jdpapi.DataProtector;
import net.sourceforge.jdpapi.DPAPIException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WindowsLocalCipherProvider
 *
 * This is a LocalCipherProvider that uses 
 * the Windows Data Protection API to store a local 
 * key on the filesystem used to encrypt/decrypt
 * local data.
 *
 */
public class WindowsLocalCipherProvider extends AbstractAESLocalCipherProvider {
    
    public static final File DEFAULT_KEY_FILE = 
        new File(LanternUtils.configDir(), "cipher.dpk");

    private static AtomicReference<Boolean> DPAPI_INITIALIZED = 
        new AtomicReference<Boolean>();

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final File keyFile;

    public WindowsLocalCipherProvider() {
        this(DEFAULT_KEY_FILE, DEFAULT_CIPHER_PARAMS_FILE);
    }
    
    public WindowsLocalCipherProvider(final File keyFile, final File cipherParamsFile) {
        super(cipherParamsFile);
        this.keyFile = keyFile;
    }

    private void initDPAPI() throws IOException {
        synchronized(DPAPI_INITIALIZED) {
            Boolean initialized = DPAPI_INITIALIZED.get();
            if (initialized == null || initialized.booleanValue() == false) {
                // ldump jdpapi DLL in a temporary folder
                // then load it.
                File tempDir = null;
                InputStream is = null; 
                try {
                    tempDir = Files.createTempDir();
                    final File tempDLL = new File(tempDir, "jdpapi.dll"); 
                    is = DataProtector.class.getResourceAsStream("/jdpapi.dll");
                    FileUtils.copyInputStreamToFile(is, tempDLL);
                    System.load(tempDLL.getAbsolutePath());
                    DPAPI_INITIALIZED.set(Boolean.TRUE);
                }
                finally {
                    FileUtils.deleteQuietly(tempDir);
                    IOUtils.closeQuietly(is);
                }
            }
        }
    }

    byte[] loadKeyData() throws IOException, GeneralSecurityException {
        initDPAPI();
        try {
            final DataProtector dpapi = new DataProtector(false);
            final Base64 base64 = new Base64();
            final byte [] encryptedKey = FileUtils.readFileToByteArray(keyFile);
            final String encodedKey = dpapi.unprotect(encryptedKey);
            return base64.decode(encodedKey.getBytes());
        } catch (DPAPIException e) {
            throw new GeneralSecurityException(e);
        }
    }

    void storeKeyData(byte [] key) throws IOException, GeneralSecurityException {
        initDPAPI();
        try {
            final DataProtector dpapi = new DataProtector(false);
            final Base64 base64 = new Base64();
            final String encodedKey = new String(base64.encode(key));
            final byte [] encryptedKey = dpapi.protect(encodedKey);
            FileUtils.writeByteArrayToFile(keyFile, encryptedKey);
        } catch (DPAPIException e) {
            throw new GeneralSecurityException(e);
        }
    }
}