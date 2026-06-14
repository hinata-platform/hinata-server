package hn.asta.hivora.config;

import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Mutual-TLS / X.509 transport security for MongoDB (OWASP A02/A05 — the H2
 * remediation). When {@code hivora.mongodb.tls.enabled=true} the driver:
 * <ul>
 *   <li>verifies the mongod server certificate against the configured CA
 *       (truststore), and</li>
 *   <li>presents the application's client certificate (keystore) so the server
 *       can authenticate it via {@code MONGODB-X509} against {@code $external}.</li>
 * </ul>
 * The connection string still controls TLS/authMechanism; this class only
 * supplies the {@link SSLContext} the driver cannot build from a URI alone.
 */
@Configuration
@ConditionalOnProperty(prefix = "hivora.mongodb.tls", name = "enabled", havingValue = "true")
public class MongoTlsConfig {

	@Bean
	public MongoClientSettingsBuilderCustomizer mongoX509TlsCustomizer(HivoraProperties properties) {
		HivoraProperties.Mongodb.Tls tls = properties.getMongodb().getTls();
		SSLContext sslContext = buildSslContext(tls);
		return builder -> builder.applyToSslSettings(ssl -> ssl.enabled(true).context(sslContext));
	}

	private SSLContext buildSslContext(HivoraProperties.Mongodb.Tls tls) {
		try {
			KeyManagerFactory kmf = null;
			if (tls.getKeyStore() != null && !tls.getKeyStore().isBlank()) {
				char[] pw = chars(tls.getKeyStorePassword());
				KeyStore ks = load(tls.getKeyStore(), pw);
				kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				kmf.init(ks, pw);
			}
			TrustManagerFactory tmf = null;
			if (tls.getTrustStore() != null && !tls.getTrustStore().isBlank()) {
				KeyStore ts = load(tls.getTrustStore(), chars(tls.getTrustStorePassword()));
				tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(ts);
			}
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf != null ? kmf.getKeyManagers() : null,
					tmf != null ? tmf.getTrustManagers() : null, null);
			return context;
		}
		catch (Exception ex) {
			throw new IllegalStateException(
					"Failed to initialise MongoDB X.509/TLS context: " + ex.getMessage(), ex);
		}
	}

	private static KeyStore load(String path, char[] password) throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(Path.of(path))) {
			store.load(in, password);
		}
		return store;
	}

	private static char[] chars(String value) {
		return value == null ? new char[0] : value.toCharArray();
	}
}
