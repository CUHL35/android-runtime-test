import java.io.File;
import java.io.FileInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Small JDK-side preflight used before assembleRelease. Never prints passwords. */
public final class SigningKeyCheck {
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    private static void fail(String message) {
        System.err.println("KEYCHECK_ERROR=" + message);
        System.exit(2);
    }

    public static void main(String[] args) {
        if (args.length != 1) fail("Thiếu đường dẫn JKS/keystore");
        File file = new File(args[0]);
        if (!file.isFile()) fail("Không tìm thấy JKS/keystore");

        String storePass = env("STORE_PASS");
        String requestedAlias = env("KEY_ALIAS").trim();
        String keyPass = env("KEY_PASS");
        if (storePass.isEmpty()) fail("Thiếu Store password");
        if (keyPass.isEmpty()) keyPass = storePass;

        String[] types = {"JKS", "PKCS12"};
        String lastProblem = "Không mở được keystore";

        for (String type : types) {
            try {
                KeyStore store = KeyStore.getInstance(type);
                try (FileInputStream input = new FileInputStream(file)) {
                    store.load(input, storePass.toCharArray());
                }

                List<String> keyAliases = new ArrayList<>();
                Enumeration<String> aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (store.isKeyEntry(alias)) keyAliases.add(alias);
                }
                if (keyAliases.isEmpty()) {
                    lastProblem = "Keystore không có private-key entry";
                    continue;
                }

                String alias = requestedAlias;
                if (alias.isEmpty()) {
                    if (keyAliases.size() == 1) {
                        alias = keyAliases.get(0);
                    } else {
                        fail("Keystore có nhiều alias: " + String.join(", ", keyAliases) + ". Hãy nhập Alias.");
                    }
                }
                if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) {
                    fail("Alias không tồn tại hoặc không phải private key: " + alias);
                }

                Key key;
                try {
                    key = store.getKey(alias, keyPass.toCharArray());
                } catch (Exception wrongKeyPassword) {
                    fail("Key password sai cho alias: " + alias);
                    return;
                }
                if (key == null) fail("Không đọc được private key của alias: " + alias);

                System.out.println("KEYCHECK_OK=1");
                System.out.println("KEYCHECK_TYPE=" + type);
                System.out.println("KEYCHECK_ALIAS=" + alias);
                return;
            } catch (java.io.IOException wrongStorePasswordOrFormat) {
                String message = wrongStorePasswordOrFormat.getMessage();
                lastProblem = message == null || message.trim().isEmpty()
                        ? "Store password sai hoặc format keystore không hợp lệ"
                        : message;
            } catch (Exception unsupported) {
                String message = unsupported.getMessage();
                lastProblem = message == null || message.trim().isEmpty()
                        ? unsupported.getClass().getSimpleName()
                        : message;
            }
        }

        fail("Store password sai hoặc JKS/PKCS12 không hợp lệ: " + lastProblem);
    }
}
