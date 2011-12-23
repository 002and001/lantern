package org.lantern;

import java.io.File;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractLocalCipherProvider
 *
 * helper base class for LocalCipherProvider
 * implementations.
 *
 */
abstract class AbstractLocalCipherProvider implements LocalCipherProvider {

    public static File DEFAULT_CIPHER_PARAMS_FILE =
            new File(LanternUtils.configDir(), "cipher.params");
    
    public static final File DEFAULT_VALIDATOR_FILE = 
        new File(LanternUtils.configDir(), "cipher.validator");
    
    private static final int VALIDATOR_SALT_BYTES = 8;
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final File paramsFile;
    private final File validatorFile;
    private Key localKey = null;
    
    AbstractLocalCipherProvider() {
        this(DEFAULT_VALIDATOR_FILE, DEFAULT_CIPHER_PARAMS_FILE);
    }
    
    AbstractLocalCipherProvider(File validatorFile, File cipherParamsFile) {
        this.validatorFile = validatorFile;
        this.paramsFile = cipherParamsFile;
    }
        
    /** 
     * return the identifier for the algorithm that should be used
     * by the created Cipher and related classes -- eg "AES"
     */ 
    abstract String getAlgorithm();
    
    /**
     * return a Key representing the user's secret value. 
     * if init is true, a new key should be generated or 
     * collected from the user. 
     *
     * @param init called if a new key should be generated/requested
     *        otherwise it should be expected that the key exists.
     */
    abstract Key getLocalKey(boolean init) throws IOException, GeneralSecurityException;

    /**
     * optional cipher initialization. This is only called when no 
     * local cipher parameters have yet been established. Parameters
     * set here are stored and loaded during subsequent calls / runs.
     *
     * By default, performs default initialization via cipher.init(opmode, key)
     *
     * @param cipher
     * @param opmode
     * @param key
     * @throws GeneralSecurityException
     */
    void initializeCipher(Cipher cipher, int opmode, Key key) throws GeneralSecurityException {
        // default init
        cipher.init(opmode, key);
    }

    Cipher getCipher() throws GeneralSecurityException {
        return Cipher.getInstance(getAlgorithm());
    }

    @Override
    public synchronized Cipher newLocalCipher(int opmode) throws IOException, GeneralSecurityException {
        final boolean init = !paramsFile.isFile();
        
        if (localKey == null) {
            log.info("Retrieving local cipher key...");
            localKey = getLocalKey(init);
        }

        final Cipher cipher = getCipher();

        if (init) {
            initializeCipher(cipher, opmode, localKey);
            saveParameters(cipher);
        }
        else {
           final AlgorithmParameters params = loadParameters();
           cipher.init(opmode, localKey, params);
        }
        return cipher;
    }

    AlgorithmParameters loadParameters() throws IOException, GeneralSecurityException {
        final AlgorithmParameters params = AlgorithmParameters.getInstance(getAlgorithm());
        final byte [] encodedParams = FileUtils.readFileToByteArray(paramsFile);
        params.init(encodedParams);
        return params;
    }

    void saveParameters(final Cipher cipher) throws IOException {
        final AlgorithmParameters params = cipher.getParameters();
        final byte[] encodedParams = params.getEncoded();
        FileUtils.writeByteArrayToFile(paramsFile, encodedParams);
    }

    boolean checkKeyValid(byte[] key, byte[] validator) throws IOException, GeneralSecurityException {
        final byte[] salt = Arrays.copyOf(validator, VALIDATOR_SALT_BYTES);
        return Arrays.equals(validator, createValidator(key, salt));
    }

    byte[] createValidator(byte []key) throws GeneralSecurityException, IOException {
        // generate new salt bytes
        final byte[] salt = new byte[VALIDATOR_SALT_BYTES];
        final SecureRandom secureRandom = LanternHub.secureRandom();
        secureRandom.nextBytes(salt);
        return createValidator(key, salt);
    }

    byte[] createValidator(byte [] key, byte[] salt) throws IOException, GeneralSecurityException {
        // the validator is the concatenation of the salt value 
        // and the sha256 digest of the salt and the key
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt);
        md.update(key);
        final byte[] hash = md.digest();
        final byte[] validator = new byte[salt.length + hash.length];
        System.arraycopy(salt, 0, validator, 0, salt.length);
        System.arraycopy(hash, 0, validator, salt.length, hash.length);
        return validator; 
    }

    byte[] loadValidator() throws IOException {
        return FileUtils.readFileToByteArray(validatorFile);
    }

    /** 
     * create and store a new validator for the key given.
     */
    void storeValidator(byte []validator) throws IOException, GeneralSecurityException {
        FileUtils.writeByteArrayToFile(validatorFile, validator);
    }
    
    void zeroFill(char[] array) {
        if (array != null) {
            Arrays.fill(array, '\0');
        }
    }

    void zeroFill(byte[] array) {
        if (array != null) {
            Arrays.fill(array, (byte) 0);
        }
    }
    
}