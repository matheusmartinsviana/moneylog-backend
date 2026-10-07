package com.moneylog.infra.notification.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografia do Web Push só com a JDK (sem biblioteca de terceiros): mensagem {@code aes128gcm} (RFC 8291 / RFC 8188) e JWT VAPID {@code ES256}
 * (RFC 8292). Preparado e <b>não exercitado em dispositivo</b>: a cifra é testada por ida e volta com a chave do receptor.
 */
final class WebPushCrypto {

    static final int RECORD_SIZE = 4096;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCrypto() {
    }

    static byte[] decode(String base64Url) {
        return B64D.decode(base64Url);
    }

    static String encode(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    static PublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("chave pública P-256 não comprimida esperada (65 bytes)");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
    }

    static PrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), p256()));
    }

    static byte[] uncompressed(PublicKey key) {
        ECPoint point = ((ECPublicKey) key).getW();
        byte[] out = new byte[65];
        out[0] = 4;
        copyFixed(point.getAffineX(), out, 1);
        copyFixed(point.getAffineY(), out, 33);
        return out;
    }

    private static void copyFixed(BigInteger value, byte[] target, int offset) {
        byte[] raw = value.toByteArray();
        int start = raw[0] == 0 ? 1 : 0;
        int length = raw.length - start;
        System.arraycopy(raw, start, target, offset + 32 - length, length);
    }

    /** Corpo {@code aes128gcm}: {@code salt(16) | rs(4) | idlen(1) | keyid(65) | texto cifrado}, um único registro. */
    static byte[] encrypt(byte[] payload, byte[] receiverPublic, byte[] authSecret) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair sender = generator.generateKeyPair();
        byte[] senderPublic = uncompressed(sender.getPublic());
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(payload, receiverPublic, authSecret, sender.getPrivate(), senderPublic, salt);
    }

    /** Versão determinística (chaves e sal dados): usada pelo teste com o vetor da RFC 8291 §5. */
    static byte[] encrypt(byte[] payload, byte[] receiverPublic, byte[] authSecret, PrivateKey senderPrivate, byte[] senderPublic, byte[] salt)
            throws GeneralSecurityException {
        byte[] secret = ecdh(senderPrivate, publicKey(receiverPublic));
        byte[] prkKey = hmac(authSecret, secret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.UTF_8), receiverPublic, senderPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8), new byte[] {1})), 12);
        byte[] padded = concat(payload, new byte[] {2});
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cipherText = cipher.doFinal(padded);
        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + senderPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) senderPublic.length).put(senderPublic);
        return concat(header.array(), cipherText);
    }

    /** Inverso, para o teste: decifra um corpo {@code aes128gcm} com a chave do receptor. */
    static byte[] decrypt(byte[] body, PrivateKey receiverPrivate, byte[] receiverPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idLength = body[20] & 0xFF;
        byte[] senderPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
        byte[] cipherText = Arrays.copyOfRange(body, 21 + idLength, body.length);
        byte[] secret = ecdh(receiverPrivate, publicKey(senderPublic));
        byte[] prkKey = hmac(authSecret, secret);
        byte[] ikm = hmac(prkKey, concat(concat("WebPush: info\0".getBytes(StandardCharsets.UTF_8), receiverPublic, senderPublic), new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8), new byte[] {1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(cipherText);
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) {
            end--;
        }
        return Arrays.copyOf(padded, end); // descarta o delimitador 0x02
    }

    /** JWT VAPID: {@code aud} = origem do endpoint, {@code exp} em segundos epoch, {@code sub} = mailto:/https: de contato. */
    static String vapidJwt(String audience, long expiresAtEpochSeconds, String subject, PrivateKey vapidPrivate) throws GeneralSecurityException {
        String header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encode(("{\"aud\":\"" + escape(audience) + "\",\"exp\":" + expiresAtEpochSeconds + ",\"sub\":\"" + escape(subject) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(vapidPrivate);
        signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + encode(signature.sign());
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] ecdh(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
