package offlinepayment.hackathon.offpay;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.os.Handler;
import android.widget.Toast;

import com.android.volley.Cache;
import com.android.volley.Network;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.BasicNetwork;
import com.android.volley.toolbox.DiskBasedCache;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.JsonObjectRequest;
import com.libra.sinvoice.Common;
import com.libra.sinvoice.LogHelper;
import com.libra.sinvoice.SinVoicePlayer;
import com.libra.sinvoice.SinVoiceRecognition;
import com.pingplusplus.android.Pingpp;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import butterknife.Bind;
import butterknife.ButterKnife;

public class MainActivity extends AppCompatActivity implements SinVoicePlayer.Listener, SinVoiceRecognition.Listener {

    private final static String TAG = "MainActivity";
    private final static int MSG_SET_RECG_TEXT = 1;
    private final static int MSG_RECG_START = 2;
    private final static int MSG_RECG_END = 3;
    private final static int MSG_PLAY_TEXT = 4;

    static {
        System.loadLibrary("sinvoice");
    }

    private final static int[] TOKENS = { 32, 32, 32, 32, 32, 32 };
    private final static int TOKEN_LEN = TOKENS.length;

    private Handler mHandler;

    @Bind(R.id.btnCharge) Button btnCharge;
    @Bind(R.id.btnPay) Button btnPay;
    @Bind(R.id.txtBalance) TextView txtBalance;

    RequestQueue requestQueue;
    String order_id;
    String words_to_send = "";
    int moneyToCharge;
    int balance;
    AlertDialog.Builder dialogPay;

    SinVoicePlayer sinVoicePlayer;
    SinVoiceRecognition sinVoiceRecognition;
    private char mRecgs[] = new char[100];
    private int mRecgCount;

    SharedPreferences preferences;

    public void updateBalance(){
        balance = preferences.getInt("Money", 0);
        txtBalance.setText(String.format(Locale.ENGLISH, "Balance: ￥%d.%d", balance/100, balance%100));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        preferences = MainActivity.this.getSharedPreferences("Balance", Context.MODE_PRIVATE);
        updateBalance();

        sinVoicePlayer = new SinVoicePlayer();
        sinVoiceRecognition = new SinVoiceRecognition();

        sinVoicePlayer.init(this);
        sinVoiceRecognition.init(this);

        sinVoicePlayer.setListener(this);
        sinVoiceRecognition.setListener(this);

        mHandler = new RegHandler(this);
        Cache cache = new DiskBasedCache(getCacheDir(), 1024*1024);
        Network network = new BasicNetwork(new HurlStack());
        requestQueue = new RequestQueue(cache, network);
        requestQueue.start();



        btnCharge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LayoutInflater inflater = getLayoutInflater();
                final View layout = inflater.inflate(R.layout.dialog_charge, (ViewGroup) findViewById(R.id.dialog));
                new AlertDialog.Builder(MainActivity.this)
                        .setIcon(R.mipmap.ic_launcher)
                        .setTitle("Charge")
                        .setView(layout)
                        .setPositiveButton("Charge", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                EditText edtCharge = (EditText) layout.findViewById(R.id.edtCharge);
                                int money = (Integer.parseInt(edtCharge.getText().toString())) * 100;
                                Map<String, Object> params = new HashMap<String, Object>();
                                moneyToCharge = money;
                                params.put("money", moneyToCharge);
                                params.put("channel", "wx");
                                final JsonObjectRequest jsonObjectRequest = new JsonObjectRequest
                                        (Request.Method.POST, "http://heckpsi.com:8080/charge", new JSONObject(params), new Response.Listener<JSONObject>() {
                                            @Override
                                            public void onResponse(JSONObject response) {
                                                try {
                                                    order_id = response.getString("order_no");
                                                    final WebSocketClient webSocketClient = new WebSocketClient(
                                                            new URI("ws://heckpsi.com:8080/waiting/" + order_id)
                                                    ) {
                                                        @Override
                                                        public void onOpen(ServerHandshake handshakedata) {

                                                        }

                                                        @Override
                                                        public void onMessage(String message) {
                                                            if (message.equals("{\"status\":\"charged\"}")){
                                                                balance += moneyToCharge;
                                                                SharedPreferences.Editor editor = preferences.edit();
                                                                editor.putInt("Money", balance);
                                                                editor.apply();
                                                                MainActivity.this.runOnUiThread(new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        updateBalance();
                                                                    }
                                                                });
                                                                this.close();
                                                            }
                                                        }

                                                        @Override
                                                        public void onClose(int code, String reason, boolean remote) {

                                                        }

                                                        @Override
                                                        public void onError(Exception ex) {

                                                        }
                                                    };
                                                    webSocketClient.connect();
                                                    Pingpp.createPayment(MainActivity.this, response.toString());
                                                } catch (Exception e){
                                                    e.printStackTrace();
                                                }

                                            }
                                        }, new Response.ErrorListener() {
                                            @Override
                                            public void onErrorResponse(VolleyError error) {

                                            }
                                        });
                                requestQueue.add(jsonObjectRequest);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .create().show();
            }
        });

        btnPay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                superSonicPlay("WTP");
            }
        });

    }

    @Override
    public void onSinToken(int[] tokens){

    }

    @Override
    public void onSinVoiceRecognitionStart() {
        mHandler.sendEmptyMessage(MSG_RECG_START);
        Log.d("OK", "Hahaha");
    }

    @Override
    public void onSinVoiceRecognition(char ch) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_RECG_TEXT, ch, 0));
    }

    @Override
    public void onSinVoiceRecognitionEnd(int result) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_RECG_END, result, 0));
        Log.d("OK", ""+result);
    }

    @Override
    public void onSinVoicePlayStart() {
    }

    @Override
    public void onSinVoicePlayEnd() {
        Log.d("Play", "Play ended");
        if (words_to_send.equals("WTP")){
            sinVoiceRecognition.start(TOKEN_LEN, false);
        }

    }

    public void superSonicPlay(String str){
        words_to_send = str;
        try {
            byte[] strs = str.getBytes("UTF8");
            if ( null != strs ) {
                int len = strs.length;
                int []tokens = new int[len];
                int maxEncoderIndex = sinVoicePlayer.getMaxEncoderIndex();
                LogHelper.d(TAG, "maxEncoderIndex:" + maxEncoderIndex);
                String encoderText = str;
                for ( int i = 0; i < len; ++i ) {
                    if ( maxEncoderIndex < 255 ) {
                        tokens[i] = Common.DEFAULT_CODE_BOOK.indexOf(encoderText.charAt(i));
                    } else {
                        tokens[i] = strs[i];
                    }
                }
                sinVoicePlayer.play(tokens, len, false, 2000);
            } else {
                sinVoicePlayer.play(TOKENS, TOKEN_LEN, false, 2000);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private static class RegHandler extends Handler {
        private StringBuilder mTextBuilder = new StringBuilder();
        private MainActivity mAct;

        public RegHandler(MainActivity act) {
            mAct = act;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_RECG_TEXT:
                    char ch = (char) msg.arg1;
//                mTextBuilder.append(ch);
                    mAct.mRecgs[mAct.mRecgCount++] = ch;
                    break;

                case MSG_RECG_START:
//                mTextBuilder.delete(0, mTextBuilder.length());
                    mAct.mRecgCount = 0;
                    break;

                case MSG_RECG_END:
                    LogHelper.d(TAG, "recognition end gIsError:" + msg.arg1);
                    if ( mAct.mRecgCount > 0 ) {
                        byte[] strs = new byte[mAct.mRecgCount];
                        for ( int i = 0; i < mAct.mRecgCount; ++i ) {
                            strs[i] = (byte)mAct.mRecgs[i];
                        }
                        try {
                            String strReg = new String(strs, "UTF8");
                            if (msg.arg1 >= 0) {
                                Log.d(TAG, "reg ok!!!!!!!!!!!!");
                                if (null != mAct) {
                                    Toast.makeText(mAct, strReg, Toast.LENGTH_SHORT).show();

                                    Log.d("OK", strReg);
                                }
                            } else {
                                Log.d(TAG, "reg error!!!!!!!!!!!!!");
                                Log.d(TAG, strReg);
                                mAct.sinVoiceRecognition.stop();
                                final int price = Integer.parseInt(strReg);
                                new AlertDialog.Builder(mAct)
                                        .setIcon(R.mipmap.ic_launcher)
                                        .setTitle("Confirm")
                                        .setMessage(String.format(Locale.ENGLISH, "Are you sure to pay ￥%d.%d", price/100, price%100))
                                        .setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                int balance = mAct.preferences.getInt("Money", 0);
                                                if (balance >= price){
                                                    balance -= price;
                                                    mAct.preferences.edit().putInt("Money", balance).apply();
                                                    mAct.runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            mAct.updateBalance();
                                                        }
                                                    });
                                                    mAct.superSonicPlay("OKC");
                                                } else {
                                                    mAct.superSonicPlay("BNE");
                                                    new AlertDialog.Builder(mAct)
                                                            .setIcon(R.mipmap.ic_launcher)
                                                            .setTitle("Balance Not Enough")
                                                            .setMessage("You are not able to buy it")
                                                            .setNegativeButton("Cancel", null)
                                                            .show();
                                                }
                                            }
                                        })
                                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                mAct.superSonicPlay("BNE");
                                            }
                                        }).show();


                                //mAct.mRecognisedTextView.setText(strReg);
                                // mAct.mRegState.setText("reg err(" + msg.arg1 + ")");
                                // mAct.mRegState.setText("reg err");
                            }
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                    }
                    break;

                case MSG_PLAY_TEXT:
//                mAct.mPlayTextView.setText(mAct.mPlayText);
                    break;
            }
            super.handleMessage(msg);
        }
    }

}
