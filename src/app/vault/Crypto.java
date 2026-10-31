package app.vault;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** 加密原语：PBKDF2 派生口令密钥、AES-GCM 分块加解密、HMAC 子密钥派生。 */
final class Crypto {
    /** PBKDF2 迭代次数。 */
    static final int ITERATIONS = 210_000;
    /** 每个数据块的明文字节数。 */
    static final int CHUNK = 64 * 1024;
    /** GCM nonce 长度。 */
    static final int NONCE = 12;
    /** GCM 认证标签长度。 */
    static final int TAG = 16;

    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JDK 不支持 PBKDF2WithHmacSHA256", e);
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 由主密钥派生子密钥（按用途标签隔离）。 */
    static byte[] derive(byte[] masterKey, String label) {
        return hmac(masterKey, ("VaultBox/v1/" + label).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] encrypt(byte[] key, byte[] nonce, byte[] plain, int off, int len)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        return c.doFinal(plain, off, len);
    }

    static byte[] decrypt(byte[] key, byte[] nonce, byte[] cipher, int off, int len)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        return c.doFinal(cipher, off, len);
    }

    /** 12 字节 nonce：8 字节文件前缀 + 4 字节块计数器。 */
    static byte[] chunkNonce(byte[] prefix, int counter) {
        byte[] n = Arrays.copyOf(prefix, NONCE);
        n[8] = (byte) (counter >>> 24);
        n[9] = (byte) (counter >>> 16);
        n[10] = (byte) (counter >>> 8);
        n[11] = (byte) counter;
        return n;
    }

    static void wipe(byte[] b) {
        if (b != null) Arrays.fill(b, (byte) 0);
    }

    static void wipe(char[] c) {
        if (c != null) Arrays.fill(c, '\0');
    }
}
