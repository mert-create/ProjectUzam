package com.nexora.remote;

import android.app.Activity;
import android.app.KeyguardManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.io.IOException;

final class UnlockApproval {
    private static final String ALIAS = "mert.windows.unlock.v1";

    interface Result {
        void approved(byte[] signature);
        void failed(CharSequence reason);
    }

    private static KeyStore store() throws GeneralSecurityException, IOException {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        return keys;
    }

    static byte[] enrollmentKey() throws GeneralSecurityException, IOException {
        if (Build.VERSION.SDK_INT < 30) throw new GeneralSecurityException("Android 11 veya üstü gerekli.");
        KeyStore keys = store();
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true).build());
            generator.generateKeyPair();
        }
        return keys.getCertificate(ALIAS).getPublicKey().getEncoded();
    }

    static void approve(Activity activity, byte[] challenge, CancellationSignal cancellation, Result result)
            throws GeneralSecurityException, IOException {
        if (Build.VERSION.SDK_INT < 30 || challenge.length != 32)
            throw new GeneralSecurityException("Kilit açma isteği desteklenmiyor.");
        byte[] nonce = challenge.clone();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign((PrivateKey) store().getKey(ALIAS, null));
        BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle("Windows kilidini aç")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Vazgeç", activity.getMainExecutor(),
                        (dialog, which) -> result.failed("İptal edildi.")).build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(signer), cancellation, activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        result.failed(message);
                    }
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult auth) {
                        try {
                            Signature signature = auth.getCryptoObject().getSignature();
                            signature.update(nonce);
                            result.approved(signature.sign());
                        } catch (GeneralSecurityException error) {
                            result.failed("Onay imzalanamadı.");
                        }
                    }
                });
    }
}
