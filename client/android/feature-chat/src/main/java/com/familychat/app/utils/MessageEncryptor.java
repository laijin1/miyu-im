package com.familychat.app.utils;

import android.util.Base64;
import android.util.Log;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ECDH + AES-256-GCM end-to-end encryption.
 * Uses Java standard library only (javax.crypto, java.security).
 *
 * Key format: X509-encoded public key, PKCS8-encoded private key, both Base64.
 * Message format: Base64(12-byte IV + AES-GCM encrypted data).
 */
public class MessageEncryptor {

    private static final String TAG = "E2EE_LOG";
    private static final String EC_ALGO = "EC";
    private static final String KEY_AGREEMENT = "ECDH";
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;       // bytes
    private static final int AES_KEY_LENGTH = 32;  // bytes (256-bit)

    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate an ECDH key pair (secp256r1 curve).
     * Returns keys as Base64-encoded strings.
     */
    public static String[] generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(EC_ALGO);
            kpg.initialize(256, secureRandom); // secp256r1
            KeyPair kp = kpg.generateKeyPair();

            String publicKeyB64 = Base64.encodeToString(
                    kp.getPublic().getEncoded(), Base64.NO_WRAP);
            String privateKeyB64 = Base64.encodeToString(
                    kp.getPrivate().getEncoded(), Base64.NO_WRAP);

            Log.d(TAG, "generateKeyPair ok");
            return new String[]{publicKeyB64, privateKeyB64};
        } catch (Exception e) {
            Log.e(TAG, "generateKeyPair failed", e);
            return null;
        }
    }

    /**
     * Encrypt plaintext using ECDH + AES-256-GCM.
     *
     * @param plainText     The message to encrypt
     * @param myPrivateKeyB64  Sender's private key (Base64, PKCS8)
     * @param peerPublicKeyB64 Receiver's public key (Base64, X509)
     * @return Base64(IV + ciphertext), or null on failure
     */
    public static String encryptMessage(String plainText,
                                         String myPrivateKeyB64,
                                         String peerPublicKeyB64) {
        try {
            // Parse keys
            PrivateKey myPriv = bytesToPrivateKey(Base64.decode(myPrivateKeyB64, Base64.NO_WRAP));
            PublicKey peerPub = bytesToPublicKey(Base64.decode(peerPublicKeyB64, Base64.NO_WRAP));

            // ECDH key agreement
            KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREEMENT);
            ka.init(myPriv);
            ka.doPhase(peerPub, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive AES-256 key via SHA-256
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] aesKey = sha256.digest(sharedSecret);

            // Generate random IV
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Encrypt with AES-256-GCM
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] ciphertext = cipher.doFinal(plainText.getBytes("UTF-8"));

            // Combine IV + ciphertext
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            String result = Base64.encodeToString(combined, Base64.NO_WRAP);
            Log.d(TAG, "encrypt ok");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed", e);
            return null;
        }
    }

    /**
     * Decrypt ciphertext using ECDH + AES-256-GCM.
     *
     * @param cipherTextB64  Base64(IV + ciphertext)
     * @param myPrivateKeyB64   Receiver's private key (Base64, PKCS8)
     * @param peerPublicKeyB64  Sender's public key (Base64, X509)
     * @return Decrypted plaintext, or null on failure
     */
    public static String decryptMessage(String cipherTextB64,
                                         String myPrivateKeyB64,
                                         String peerPublicKeyB64) {
        try {
            byte[] combined = Base64.decode(cipherTextB64, Base64.NO_WRAP);
            if (combined.length < IV_LENGTH + 1) {
                Log.w(TAG, "decrypt: ciphertext too short");
                return null;
            }

            // Parse keys
            PrivateKey myPriv = bytesToPrivateKey(Base64.decode(myPrivateKeyB64, Base64.NO_WRAP));
            PublicKey peerPub = bytesToPublicKey(Base64.decode(peerPublicKeyB64, Base64.NO_WRAP));

            // ECDH key agreement
            KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREEMENT);
            ka.init(myPriv);
            ka.doPhase(peerPub, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive AES-256 key via SHA-256
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] aesKey = sha256.digest(sharedSecret);

            // Extract IV and ciphertext
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            // Decrypt
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            byte[] plainBytes = cipher.doFinal(ciphertext);

            String plainText = new String(plainBytes, "UTF-8");
            Log.d(TAG, "decrypt ok");
            return plainText;
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed", e);
            return null;
        }
    }

    // ── Key format conversion helpers ──

    private static PublicKey bytesToPublicKey(byte[] encoded) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
        KeyFactory kf = KeyFactory.getInstance(EC_ALGO);
        return kf.generatePublic(spec);
    }

    private static PrivateKey bytesToPrivateKey(byte[] encoded) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        KeyFactory kf = KeyFactory.getInstance(EC_ALGO);
        return kf.generatePrivate(spec);
    }
}
