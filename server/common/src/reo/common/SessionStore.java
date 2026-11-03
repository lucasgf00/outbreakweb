/*
    reo-web bioserver - fan server for Resident Evil Outbreak File #1/#2
    (C) 2026 reo-web contributors. AGPL-3.0-or-later, see LICENSE.txt.

    Replaces the upstream PHP login (www/login_form.php): validates userid/password
    against the `users` table and creates an 8-digit session id in `sessions`,
    which the game later presents in LOGIN 0x6101 / GSLOGIN 0x1031.

    Passwords at rest: users.passwd holds "$p1$" + base64url(6-byte salt) + base64url(15 bytes of
    PBKDF2-HMAC-SHA256, 100000 rounds) = 32 characters (fits the varchar(32) column). Rows that
    still hold a plain password are accepted once and rewritten as a hash on that login.
*/

package reo.common;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class SessionStore {
    private final GameConfig cfg;
    private final SecureRandom rnd = new SecureRandom();

    public SessionStore(GameConfig cfg) {
        this.cfg = cfg;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPassword);
    }

    /** @return the new session id, or null if the credentials are wrong. */
    public String login(String userid, String password, String ip, int port) throws SQLException {
        if (userid == null || password == null || userid.isEmpty() || password.isEmpty()) return null;
        if (!userid.matches("[A-Za-z0-9 _]{1,14}") || password.length() > 32) return null;
        try (Connection c = connect()) {
            String stored;
            try (PreparedStatement st = c.prepareStatement("select passwd from users where userid=?")) {
                st.setString(1, userid);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) return null;
                    stored = rs.getString(1);
                    if (rs.next()) return null;
                }
            }
            if (!verify(password, stored)) return null;
            if (!stored.startsWith(PREFIX)) {   // legacy plain row: upgrade it now
                try (PreparedStatement st = c.prepareStatement("update users set passwd=? where userid=? and passwd=?")) {
                    st.setString(1, hash(password));
                    st.setString(2, userid);
                    st.setString(3, stored);
                    st.executeUpdate();
                }
            }
            try (PreparedStatement st = c.prepareStatement("delete from sessions where lower(userid)=lower(?)")) {
                st.setString(1, userid);
                st.executeUpdate();
            }
            String sessid;
            for (;;) {
                sessid = String.valueOf(10000000 + rnd.nextInt(90000000));
                try (PreparedStatement st = c.prepareStatement("select count(*) from sessions where sessid=?")) {
                    st.setString(1, sessid);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) == 0) break;
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement(
                    "insert into sessions (userid,ip,port,sessid,lastlogin) values (lower(?),?,?,?,now())")) {
                st.setString(1, userid);
                st.setString(2, ip == null ? "" : ip);
                st.setInt(3, port);
                st.setString(4, sessid);
                st.executeUpdate();
            }
            return sessid;
        }
    }

    static final String PREFIX = "$p1$";
    private static final int ROUNDS = 100_000;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, ROUNDS, 15 * 8)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** "$p1$" + salt + hash, 32 characters. Also for scripts that create users. */
    public static String hash(String password) {
        byte[] salt = new byte[6];
        new SecureRandom().nextBytes(salt);
        return PREFIX + B64.encodeToString(salt) + B64.encodeToString(pbkdf2(password, salt));
    }

    static boolean verify(String password, String stored) {
        if (stored == null) return false;
        if (!stored.startsWith(PREFIX))
            return MessageDigest.isEqual(password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                         stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (stored.length() != 32) return false;
        byte[] salt, want;
        try {
            salt = Base64.getUrlDecoder().decode(stored.substring(4, 12));
            want = Base64.getUrlDecoder().decode(stored.substring(12));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(want, pbkdf2(password, salt));
    }

    /** java -cp build/reo-bioserver.jar reo.common.SessionStore <password>: prints the passwd value. */
    public static void main(String[] args) {
        System.out.println(hash(args.length > 0 ? args[0] : ""));
    }
}
