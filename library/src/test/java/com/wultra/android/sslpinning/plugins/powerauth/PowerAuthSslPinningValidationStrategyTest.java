package com.wultra.android.sslpinning.plugins.powerauth;

import android.util.Log;

import com.wultra.android.sslpinning.CertStore;
import com.wultra.android.sslpinning.CertStoreConfiguration;
import com.wultra.android.sslpinning.TestUtils;
import com.wultra.android.sslpinning.UpdateMode;
import com.wultra.android.sslpinning.UpdateResult;
import com.wultra.android.sslpinning.interfaces.CryptoProvider;
import com.wultra.android.sslpinning.interfaces.ECPublicKey;
import com.wultra.android.sslpinning.interfaces.SecureDataStore;
import com.wultra.android.sslpinning.interfaces.SignedData;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Base64;
import java.util.Date;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

import io.getlime.security.powerauth.networking.ssl.PA2ClientValidationStrategy;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for PowerAuthSslPinningValidationStrategy.
 *
 * @author Tomas Kypta, tomas.kypta@wultra.com
 */
@RunWith(PowerMockRunner.class)
@PowerMockIgnore({
        "javax.net.ssl.*",
        "javax.security.auth.x500.*"
})
@PrepareForTest({
        android.util.Base64.class,
        Log.class
})
public class PowerAuthSslPinningValidationStrategyTest {

    @Mock
    CryptoProvider cryptoProvider;

    @Mock
    SecureDataStore secureDataStore;

    @Before
    public void setUp() {
        Security.addProvider(new BouncyCastleProvider());

        PowerMockito.mockStatic(android.util.Base64.class);
        when(android.util.Base64.encodeToString(any(byte[].class), anyInt()))
                .thenAnswer(invocation ->
                        new String(java.util.Base64.getEncoder().encode((byte[]) invocation.getArgument(0)))
                );
        when(android.util.Base64.decode(anyString(), anyInt()))
                .thenAnswer(invocation ->
                        Base64.getDecoder().decode((String)invocation.getArgument(0))
                );

        PowerMockito.mockStatic(Log.class);
        when(Log.e(anyString(), anyString()))
                .then(invocation -> {
                    System.out.println((String) invocation.getArgument(1));
                    return 0;
                });
        when(cryptoProvider.hashSha256(any(byte[].class)))
                .thenAnswer(invocation -> {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    return digest.digest(invocation.getArgument(0));
                });
        when(cryptoProvider.importECPublicKey(any(byte[].class)))
                .thenAnswer(invocation ->
                        new PA2ECPublicKey(invocation.getArgument(0))
                );
        when(cryptoProvider.ecdsaValidateSignatures(any(SignedData.class), any(ECPublicKey.class)))
                .thenAnswer(invocation -> {
                    return true;
                });
    }

    @Test
    public void testPowerAuthSslPinningValidationStrategyOnGithubSuccess() throws Exception {
        String publicKey = "BC3kV9OIDnMuVoCdDR9nEA/JidJLTTDLuSA2TSZsGgODSshfbZg31MS90WC/HdbU/A5WL5GmyDkE/iks6INv+XE=";
        byte[] publicKeyBytes = java.util.Base64.getDecoder().decode(publicKey);

        CertStoreConfiguration config = TestUtils.getCertStoreConfiguration(
                new Date(),
                new String[]{"github.com"},
                new URL("https://gist.githubusercontent.com/hvge/7c5a3f9ac50332a52aa974d90ea2408c/raw/c5b021db0fcd40b1262ab513bf375e4641834925/ssl-pinning-signatures.json"),
                publicKeyBytes,
                null);
        CertStore store = new CertStore(config, cryptoProvider, secureDataStore);
        UpdateResult updateResult = store.update(UpdateMode.FORCED);
        assertEquals(UpdateResult.OK, updateResult);

        PA2ClientValidationStrategy strategy = new PowerAuthSslPinningValidationStrategy(store);

        URL url = new URL("https://github.com");
        URLConnection urlConnection = url.openConnection();
        final HttpsURLConnection sslConnection = (HttpsURLConnection) urlConnection;
        final SSLSocketFactory sslSocketFactory = strategy.getSSLSocketFactory();
        if (sslSocketFactory != null) {
            sslConnection.setSSLSocketFactory(sslSocketFactory);
        }
        final HostnameVerifier hostnameVerifier = strategy.getHostnameVerifier();
        if (hostnameVerifier != null) {
            sslConnection.setHostnameVerifier(hostnameVerifier);
        }

        verify(cryptoProvider, times(0)).hashSha256(any(byte[].class));

        sslConnection.connect();
        int response = sslConnection.getResponseCode();
        assertEquals(2, response / 100);
        sslConnection.disconnect();

        verify(cryptoProvider, times(1)).hashSha256(any(byte[].class));
        verify(secureDataStore).load(anyString());
    }

    @Test(expected = SSLHandshakeException.class)
    public void testPowerAuthSslPinningValidationStrategyOnGithubFailure() throws Exception {
        String publicKey = "BC3kV9OIDnMuVoCdDR9nEA/JidJLTTDLuSA2TSZsGgODSshfbZg31MS90WC/HdbU/A5WL5GmyDkE/iks6INv+XE=";
        byte[] publicKeyBytes = java.util.Base64.getDecoder().decode(publicKey);

        CertStoreConfiguration config = TestUtils.getCertStoreConfiguration(
                new Date(),
                new String[]{"github.com"},
                new URL("https://test.wultra.com"),
                publicKeyBytes,
                null);
        CertStore store = new CertStore(config, cryptoProvider, secureDataStore);

        PA2ClientValidationStrategy strategy = new PowerAuthSslPinningValidationStrategy(store);

        URL url = new URL("https://github.com");
        URLConnection urlConnection = url.openConnection();
        final HttpsURLConnection sslConnection = (HttpsURLConnection) urlConnection;
        final SSLSocketFactory sslSocketFactory = strategy.getSSLSocketFactory();
        if (sslSocketFactory != null) {
            sslConnection.setSSLSocketFactory(sslSocketFactory);
        }
        final HostnameVerifier hostnameVerifier = strategy.getHostnameVerifier();
        if (hostnameVerifier != null) {
            sslConnection.setHostnameVerifier(hostnameVerifier);
        }

        verify(cryptoProvider, times(0)).hashSha256(any(byte[].class));

        sslConnection.connect();
    }
}
