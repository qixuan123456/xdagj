/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.crypto;

import java.math.BigInteger;
import java.security.*;
import java.util.Arrays;

import io.xdag.utils.BytesUtils;
import io.xdag.utils.HashUtils;
import io.xdag.utils.Numeric;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.crypto.SECP256K1;
import org.apache.tuweni.io.Base58;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;

import static com.google.common.base.Preconditions.*;

/** Crypto key utilities. */
public class Keys {
    public static final int PUBLIC_KEY_SIZE = 64;
    public static final int PUBLIC_KEY_LENGTH_IN_HEX = PUBLIC_KEY_SIZE << 1;
    public static final int ADDRESS_SIZE = 160;
    public static final int ADDRESS_LENGTH_IN_HEX = ADDRESS_SIZE >> 2;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private Keys() {}

    public static SECP256K1.KeyPair createEcKeyPair() {
        return SECP256K1.KeyPair.random();
    }

    public static String getAddress(SECP256K1.PublicKey publicKey) {
        return getAddress(publicKey.toHexString());
    }

    public static String getAddress(SECP256K1.SecretKey secretKey) {
        return getAddress(SECP256K1.PublicKey.fromSecretKey(secretKey).toHexString());
    }

    public static String getAddress(BigInteger publicKey) {
        return getAddress(Numeric.toHexStringWithPrefixZeroPadded(publicKey, PUBLIC_KEY_LENGTH_IN_HEX));
    }

    public static String getAddress(String publicKey) {
        String publicKeyNoPrefix = Numeric.cleanHexPrefix(publicKey);

        if (publicKeyNoPrefix.length() < PUBLIC_KEY_LENGTH_IN_HEX) {
            publicKeyNoPrefix = StringUtils.repeat('\0', PUBLIC_KEY_LENGTH_IN_HEX - publicKeyNoPrefix.length()) + publicKeyNoPrefix;
//            publicKeyNoPrefix = Strings.zeros(PUBLIC_KEY_LENGTH_IN_HEX - publicKeyNoPrefix.length()) + publicKeyNoPrefix;
        }
        String hash = HashUtils.sha3(publicKeyNoPrefix);
        return hash.substring(hash.length() - ADDRESS_LENGTH_IN_HEX); // right most 160 bits
    }

    public static byte[] getAddress(byte[] publicKey) {
        byte[] hash = Hash.keccak256(publicKey);
        return Arrays.copyOfRange(hash, hash.length - 20, hash.length); // right most 160 bits
    }

    private static void check(boolean test, String message) {
        if (!test) {
            throw new IllegalArgumentException(message);
        }
    }

    public static byte[] signatureToAddress(byte[] messageHash, Sign.ECDSASignature sig) throws SignatureException {
        check(messageHash.length == 32, "messageHash argument has length " + messageHash.length);

        int header = sig.v;
        // The header byte: 0x1B = first key with even y, 0x1C = first key with odd y,
        // 0x1D = second key with even y, 0x1E = second key with odd y
        if (header < 27 || header > 34) {
            throw new SignatureException("Header byte out of range: " + header);
        }
        if (header >= 31) {
            header -= 4;
        }
        int recId = header - 27;

        byte[] pubBytes = recoverPubBytesFromSignature(recId, sig, messageHash);
        if (pubBytes == null) {
            throw new SignatureException("Could not recover public key from signature");
        }

        return Hash.sha3omit12(Arrays.copyOfRange(pubBytes, 1, pubBytes.length));
    }

    /** Decompress a compressed public key (x co-ord and low-bit of y-coord). */
    public static ECPoint decompressKey(BigInteger xBN, boolean yBit) {
        X9IntegerConverter x9 = new X9IntegerConverter();
        byte[] compEnc = x9.integerToBytes(xBN, 1 + x9.getByteLength(SECP256K1.Parameters.CURVE.getCurve()));
        compEnc[0] = (byte) (yBit ? 0x03 : 0x02);
        return SECP256K1.Parameters.CURVE.getCurve().decodePoint(compEnc);
    }

    /**
     * <p>
     * Given the components of a signature and a selector value, recover and return
     * the public key that generated the signature according to the algorithm in
     * SEC1v2 section 4.1.6.
     * </p>
     *
     * <p>
     * The recId is an index from 0 to 3 which indicates which of the 4 possible
     * keys is the correct one. Because the key recovery operation yields multiple
     * potential keys, the correct key must either be stored alongside the
     * signature, or you must be willing to try each recId in turn until you find
     * one that outputs the key you are expecting.
     * </p>
     *
     * <p>
     * If this method returns null it means recovery was not possible and recId
     * should be iterated.
     * </p>
     *
     * <p>
     * Given the above two points, a correct usage of this method is inside a for
     * loop from 0 to 3, and if the output is null OR a key that is not the one you
     * expect, you try again with the next recId.
     * </p>
     *
     * @param recId
     *            Which possible key to recover.
     * @param sig
     *            the R and S components of the signature, wrapped.
     * @param messageHash
     *            Hash of the data that was signed.
     * @return 65-byte encoded public key
     */
    public static byte[] recoverPubBytesFromSignature(int recId, Sign.ECDSASignature sig, byte[] messageHash) {
        checkArgument(recId >= 0, "recId must be positive");
        checkArgument(sig.r.signum() >= 0, "r must be positive");
        checkArgument(sig.s.signum() >= 0, "s must be positive");
        checkArgument(messageHash != null, "messageHash must not be null");
        // 1.0 For j from 0 to h (h == recId here and the loop is outside this function)
        // 1.1 Let x = r + jn
        BigInteger n = SECP256K1.Parameters.CURVE.getN(); // Curve order.
        BigInteger i = BigInteger.valueOf((long) recId / 2);
        BigInteger x = sig.r.add(i.multiply(n));
        // 1.2. Convert the integer x to an octet string X of length mlen using the
        // conversion routine
        // specified in Section 2.3.7, where mlen = ⌈(log2 p)/8⌉ or mlen = ⌈m/8⌉.
        // 1.3. Convert the octet string (16 set binary digits)||X to an elliptic curve
        // point R using the
        // conversion routine specified in Section 2.3.4. If this conversion routine
        // outputs “invalid”, then
        // do another iteration of Step 1.
        //
        // More concisely, what these points mean is to use X as a compressed public
        // key.
        ECCurve.Fp curve = (ECCurve.Fp) SECP256K1.Parameters.CURVE.getCurve();
        BigInteger prime = curve.getQ(); // Bouncy Castle is not consistent about the letter it uses for the prime.
        if (x.compareTo(prime) >= 0) {
            // Cannot have point co-ordinates larger than this as everything takes place
            // modulo Q.
            return null;
        }
        // Compressed keys require you to know an extra bit of data about the y-coord as
        // there are two possibilities.
        // So it's encoded in the recId.
        ECPoint R = decompressKey(x, (recId & 1) == 1);
        // 1.4. If nR != point at infinity, then do another iteration of Step 1 (callers
        // responsibility).
        if (!R.multiply(n).isInfinity())
            return null;
        // 1.5. Compute e from M using Steps 2 and 3 of ECDSA signature verification.
        BigInteger e = new BigInteger(1, messageHash);
        // 1.6. For k from 1 to 2 do the following. (loop is outside this function via
        // iterating recId)
        // 1.6.1. Compute a candidate public key as:
        // Q = mi(r) * (sR - eG)
        //
        // Where mi(x) is the modular multiplicative inverse. We transform this into the
        // following:
        // Q = (mi(r) * s ** R) + (mi(r) * -e ** G)
        // Where -e is the modular additive inverse of e, that is z such that z + e = 0
        // (mod n). In the above equation
        // ** is point multiplication and + is point addition (the EC group operator).
        //
        // We can find the additive inverse by subtracting e from zero then taking the
        // mod. For example the additive
        // inverse of 3 modulo 11 is 8 because 3 + 8 mod 11 = 0, and -3 mod 11 = 8.
        BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
        BigInteger rInv = sig.r.modInverse(n);
        BigInteger srInv = rInv.multiply(sig.s).mod(n);
        BigInteger eInvrInv = rInv.multiply(eInv).mod(n);
        ECPoint.Fp q = (ECPoint.Fp) ECAlgorithms.sumOfTwoMultiplies(SECP256K1.Parameters.CURVE.getG(), eInvrInv, R, srInv);
        // result sanity check: point must not be at infinity
        if (q.isInfinity())
            return null;
        return q.getEncoded(/* compressed */ false);
    }

    public static byte[] toBytesAddress(SECP256K1.KeyPair key) {
        return Hash.sha256hash160(Bytes.wrap(key.publicKey().bytesArray()));
    }

    public static String toBase58Address(SECP256K1.KeyPair key) {
        byte[] addrBytes = toBytesAddress(key);
        return Base58.encode(Bytes.wrap(addrBytes));
    }

    /**
     * This is the generic Signature exception.
     */
    public static class SignatureException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;

        public SignatureException(String msg) {
            super(msg);
        }
    }

}

