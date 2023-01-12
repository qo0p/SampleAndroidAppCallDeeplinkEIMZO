package uz.yt.sample.myapplication;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivity {

    // !!! CHANGE IT TO YOUR DOMAIN, WHICH RUNS REST-API, DO NOT USE THIS m.e-imzo.uz DOMAIN IN PRODUCTION
    private static String DOMAIN = "m.e-imzo.uz";
    private static String AUTH_URL = "https://" + DOMAIN + "/frontend/auth";
    private static String STATUS_URL = "https://" + DOMAIN + "/frontend/status";

    // !!! CHANGE IT TO YOUR BACKEND API
    private static String AUTH_RESULT_URL = "https://" + DOMAIN + "/demo2/user_auth_result.php";

    private final int STATUS_CHECK_EACH_SECOND = 5;
    private final int STATUS_CHECK_INTERVAL = STATUS_CHECK_EACH_SECOND * 1000;
    private final int STATUS_CHECK_LIMIT = 2 * 60 / STATUS_CHECK_EACH_SECOND;


    TextView statusText;
    EditText resultText;

    final void updateStatusText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(text);
            }
        });
    }

    final void updateResultText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultText.setText(text);
            }
        });
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.tvStatus);
        resultText = findViewById(R.id.etResult);

        Button authButton = findViewById(R.id.bAuth);
        authButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        updateStatusText("");
                        updateResultText("");
                        try {
                            RawResult res = post(new URL(AUTH_URL), new byte[0]);
                            Log.d("auth http status", "HTTP " + res.responseCode + " - " + res.responseMessage);
                            Log.d("auth http body", res.responseBody);
                            if (res.responseCode != 200) {
                                throw new Exception("HTTP " + res.responseCode + " - " + res.responseMessage);
                            }
                            updateResultText(res.responseBody);


                            JSONObject jsonObject = new JSONObject(res.responseBody);
                            int status = jsonObject.getInt("status");
                            String message = jsonObject.getString("message");
                            if (status != 1) {
                                throw new Exception("AUTH STATUS " + status + " - " + message);
                            }

                            String siteId = jsonObject.getString("siteId");
                            final String documentId = jsonObject.getString("documentId");
                            String challange = jsonObject.getString("challange");

                            Log.d("auth siteId", siteId);
                            Log.d("auth documentId", documentId);
                            Log.d("auth challange", challange);

                            byte[] data = challange.getBytes();
                            OzDSt1106Digest digest = new OzDSt1106Digest();
                            digest.reset();
                            digest.update(data, 0, data.length);

                            byte[] hash = new byte[digest.getDigestSize()];
                            digest.doFinal(hash, 0);

                            String hashString = Hex.encode(hash);
                            Log.d("auth hashString", hashString);

                            String qrBody = siteId + documentId + hashString;

                            CRC32 crc = new CRC32();
                            crc.update(Hex.decode(qrBody));
                            long c1 = crc.getValue();
                            String crc32 = zerePad(Long.toHexString(c1), 8);

                            Log.d("auth crc32", "crc32(" + qrBody + ") = " + crc32);

                            String qrCode = qrBody + crc32;
                            Log.d("auth qrCode", qrCode);

                            String deepLink = "eimzo://sign?qc=" + qrCode;
                            Log.d("auth deepLink", deepLink);


                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(deepLink));
                            startActivity(browserIntent);


                            Log.d("auth status", "run get status thread");

                            pollStatus(documentId, new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        getAuthResult(documentId);
                                    } catch (Throwable e) {
                                        Log.d("auth result error", e.getClass().getSimpleName() + ": " + e.getMessage());
                                        updateStatusText(e.getClass().getSimpleName() + ": " + e.getMessage());
                                    }
                                }
                            });


                        } catch (Throwable e) {
                            Log.d("auth error", e.getClass().getSimpleName() + ": " + e.getMessage());
                            updateStatusText(e.getClass().getSimpleName() + ": " + e.getMessage());

                        }
                    }
                });
                t.start();

            }
        });

    }

    Pattern fetchJson = Pattern.compile("\\n\\s*(\\{.*\\})\\n*<");

    void getAuthResult(String documentId) throws Exception {
        String postData2 = "documentId=" + documentId;
        RawResult res2 = get(new URL(AUTH_RESULT_URL + "?documentId=" + documentId));
        Log.d("auth result http status", "HTTP " + res2.responseCode + " - " + res2.responseMessage);
        Log.d("auth result http body", res2.responseBody);
        if (res2.responseCode != 200) {
            throw new Exception("HTTP " + res2.responseCode + " - " + res2.responseMessage);
        }

        // !!! DO NOT USE THIS WAY, AS IT IS JUST FOR DEMO
        Matcher m = fetchJson.matcher(res2.responseBody);
        boolean hasJson = m.find();
        int groupCount = m.groupCount();
        Log.d("auth result parse", "hasJson= " + hasJson + ", groupCount=" + groupCount);
        if (hasJson && groupCount > 0) {
            updateResultText(m.group(1));
        } else {
            updateResultText(res2.responseBody);
        }
    }

    void pollStatus(String documentId, Runnable onSccess) {
        Thread statusThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int checkCount = STATUS_CHECK_LIMIT;
                try {
                    while (checkCount > 0) {

                        Thread.sleep(STATUS_CHECK_INTERVAL);

                        String postData = "documentId=" + documentId;

                        RawResult res = post(new URL(STATUS_URL), postData.getBytes());
                        Log.d("auth status number", String.valueOf(checkCount));
                        Log.d("auth status http status", "HTTP " + res.responseCode + " - " + res.responseMessage);
                        Log.d("auth status http body", res.responseBody);
                        if (res.responseCode != 200) {
                            throw new Exception("HTTP " + res.responseCode + " - " + res.responseMessage);
                        }
                        updateResultText(res.responseBody);

                        JSONObject jsonObject = new JSONObject(res.responseBody);
                        int status = jsonObject.getInt("status");
                        String message = jsonObject.getString("message");
                        if (status != 1 && status != 2) {
                            throw new Exception("STATUS " + status + " - " + message);
                        }

                        if (status == 1) {

                            onSccess.run();

                            break;
                        }

                        checkCount--;
                    }
                } catch (Throwable e) {
                    Log.d("auth status error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    updateStatusText(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                if(checkCount == 0){
                    Log.d("auth status error", "timeout");
                    updateStatusText("auth status timeout");
                }
            }
        });

        statusThread.start();
    }

    static String zerePad(String s, int count) {
        if (s.length() >= count) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count - s.length(); i++) {
            sb.append("0");
        }
        sb.append(s);
        return sb.toString();
    }

    public static class RawResult {

        public int responseCode;
        public String responseMessage;
        public String responseBody;
    }

    public static RawResult post(URL url, byte[] body) throws IOException {
        RawResult r = new RawResult();
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setRequestProperty("Content-Length", String.valueOf(body.length));
        con.setDoOutput(true);
        try (OutputStream out = con.getOutputStream()) {
            out.write(body);
            out.flush();
            r.responseCode = con.getResponseCode();
            r.responseMessage = con.getResponseMessage();

            StringBuilder sb = new StringBuilder();

            InputStreamReader in = new InputStreamReader(con.getInputStream());
            BufferedReader br = new BufferedReader(in);
            String text = "";
            while ((text = br.readLine()) != null) {
                sb.append(text).append("\n");
            }
            r.responseBody = sb.toString();
        } finally {
            con.disconnect();
        }
        return r;
    }

    public static RawResult get(URL url) throws IOException {
        RawResult r = new RawResult();
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        r.responseCode = con.getResponseCode();
        r.responseMessage = con.getResponseMessage();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {

            StringBuilder sb = new StringBuilder();
            String text = "";
            while ((text = br.readLine()) != null) {
                sb.append(text).append("\n");
            }
            r.responseBody = sb.toString();
        } finally {
            con.disconnect();
        }
        return r;
    }

}