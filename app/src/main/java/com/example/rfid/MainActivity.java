package com.example.rfid;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.device.DeviceManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.rfid.trans.OtgUtils;
import com.rfid.trans.ReadTag;
import com.rfid.trans.ReaderHelp;
import com.rfid.trans.ReaderParameter;
import com.rfid.trans.TagCallback;
import java.util.HashSet;

import android.media.ToneGenerator;
import android.media.AudioManager;

import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final int REQUEST_MEDIA_PERMISSIONS = 2;
    private ReaderHelp readerHelp;
    private final String comPort = "/dev/ttyHSL0";
    private final int logSwitch = 1;  // Defina o valor do logSwitch, conforme necessário
    public static int baud = 57600;  // Defina o baud rate inicial como 57600
    private boolean keyPress = false; // Variável para controlar o estado da tecla
    private VirtualKeyListenerBroadcastReceiver mVirtualKeyListenerBroadcastReceiver;
    private boolean isStopThread = false;
    private EditText etTags;
    private TextView tvTotalTags;
    private int cardNumber = 0;  // Adicione esta variável para contar as tags
    private HashSet<String> readTags;  // Conjunto para armazenar tags lidas
    private SeekBar seekBarPower;
    private final int MAX_POWER = 33;  // Máximo valor de potência (33)
    private ToneGenerator toneGenerator;


    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inicializa a instância do ReaderHelp
        readerHelp = new ReaderHelp();

        readTags = new HashSet<>();  // Inicializa o conjunto

        // Verifica permissões de armazenamento
        verifyStoragePermissions();

        // Inicializa o GPIO
        initGpio();

        // Inicializa o SeekBar
        seekBarPower = findViewById(R.id.seekBarPower);

        seekBarPower.setProgress(MAX_POWER);

        // Configura o valor de potência inicial para o máximo assim que o app abrir
        setReaderPower(MAX_POWER);

        // Define o comportamento do SeekBar ao ser deslizado
        seekBarPower.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Chama a função SetRfPower com o valor do SeekBar (progress)
                setReaderPower(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Não precisamos de nada aqui por enquanto
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Quando o usuário soltar o SeekBar, você pode realizar outra ação, se necessário
            }
        });


        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        tvTotalTags = findViewById(R.id.tvTotal);

        etTags = findViewById(R.id.etTags);

        Button btnClear = findViewById(R.id.btnClear);

        TextView tvSettings = findViewById(R.id.tvSettings);
        tvSettings.setOnClickListener(v -> {
            // Ação ao clicar em "Configurações"
            Toast.makeText(this, "Abrindo configurações...", Toast.LENGTH_SHORT).show();
            // Aqui você pode adicionar a lógica para abrir a tela de configurações
        });

        // Configura o BroadcastReceiver para eventos do sistema
        mVirtualKeyListenerBroadcastReceiver = new VirtualKeyListenerBroadcastReceiver();
        registerReceiver(mVirtualKeyListenerBroadcastReceiver, new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS"), Context.RECEIVER_NOT_EXPORTED);

        btnClear.setVisibility(View.GONE);

    // Adicionar um TextWatcher para monitorar mudanças no campo etTags
        etTags.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Não precisamos fazer nada aqui
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Mostrar o botão "Limpar" quando houver algo no campo etTags
                if (s.length() > 0) {
                    btnClear.setVisibility(View.VISIBLE);  // Exibe o botão
                } else {
                    btnClear.setVisibility(View.GONE);     // Oculta o botão se o campo estiver vazio
                }
            }


            @Override
            public void afterTextChanged(Editable s) {
                // Não precisamos fazer nada aqui
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Limpa o conteúdo do EditText e do TextView
                etTags.setText("");
                tvTotalTags.setText("Total: 0");

                btnClear.setVisibility(View.GONE);
                // Aqui você pode adicionar mais lógica, se necessário
                readTags.clear(); // Caso use uma lista para armazenar as tags lidas
                cardNumber = 0; // Reseta o contador de tags lidas
            }
        });
        displayDeviceInfo(tvSettings);
    }

    private void setReaderPower(int power) {
        try {
            // Bit7 = 0 indica que a configuração deve ser salva após o desligamento
            // Para fazer isso, usamos bitwise OR para garantir que o Bit7 seja 0
            int powerConfig = power & 0x7F;  // Garante que o Bit7 seja 0

            // Chama a função SetRfPower do ReaderHelp com o valor de potência ajustado
            int result = readerHelp.SetRfPower((byte) powerConfig);

            if (result == 0) {
                Log.d("RFID", "Potência ajustada com sucesso: " + power);
            } else {
                Log.e("RFID", "Erro ao ajustar potência, código: " + result);
            }
        } catch (Exception e) {
            Log.e("RFID", "Erro ao ajustar a potência do leitor RFID", e);
        }
    }

    private void displayDeviceInfo(TextView tvSettings) {
        String deviceInfo = "Modelo: " + Build.MODEL + "\n" +
                "Fabricante: " + Build.MANUFACTURER + "\n" +
                "Versão do Android: " + Build.VERSION.RELEASE;

        //Toast.makeText(this, deviceInfo, Toast.LENGTH_LONG).show();

        // Verifica se o modelo começa com "RFID"
        if (Build.MODEL.startsWith("RFID")) {
            connectToSerialPort();
            tvSettings.setVisibility(View.VISIBLE); // Torna visível o TextView "Configurações"
        } else {
            tvSettings.setVisibility(View.GONE); // Esconde caso não comece com "RFID"
        }
    }

    private void connectToSerialPort() {
        try {
            if (readerHelp.Connect(comPort, 57600, logSwitch) == 0) {
                showToast("Conectado com sucesso a 57600 baud");
                initRfid();
            } else if (readerHelp.Connect(comPort, 115200, logSwitch) == 0) {
                showToast("Conectado com sucesso a 115200 baud");
                initRfid();
            } else {
                Toast.makeText(MainActivity.this, "Falha ao conectar", Toast.LENGTH_SHORT).show();
                Log.e("RFID", "Erro na conexão com ambos os baud rates.");
            }
        } catch (Exception e) {
            Log.e("RFID", "Erro ao tentar conectar à porta serial", e);
            Toast.makeText(MainActivity.this, "Erro ao tentar conectar à porta serial", Toast.LENGTH_SHORT).show();
        }
    }
    private void showToast(String message) {
        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
    }


    private void startReadingRFID() {
        Log.d("RFID", "Iniciando a leitura de tags RFID...");

        isStopThread = false;

        new Thread(() -> {
            while (!isStopThread) {
                readerHelp.StartRead();
                readerHelp.SetCallBack(new TagCallback() {
                    @Override
                    public void tagCallback(ReadTag readTag) {
                        String rfidCode = readTag.epcId != null ? readTag.epcId.toUpperCase() : "Tag não encontrada";
                        int rssiValue = readTag.rssi;  // Acesso ao RSSI
                        //String rssiDisplay = (rssiValue != -1) ? String.valueOf(rssiValue) : "N/A";
                        runOnUiThread(() -> {
                            if (!readTags.contains(rfidCode)) {
                                readTags.add(rfidCode);
                                cardNumber++;
                                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 200);  // Duração de 150ms
                                // Atualiza o campo de texto etTags
                                String currentTags = etTags.getText().toString();
                                if (!currentTags.isEmpty()) {
                                    currentTags += "\n";
                                }
                                String rssiDisplay = (rssiValue != -1) ? rssiValue + "%" : "N/A";
                                currentTags += rfidCode + "    " + rssiDisplay;
                                etTags.setText(currentTags);
                                etTags.setSelection(etTags.getText().length());

                                // Atualiza o TextView "Total"
                                tvTotalTags.setText("Total: " + cardNumber);
                            } else {
                                Log.d("RFID", "Tag já lida: " + rfidCode);
                            }
                        });
                    }

                    @Override
                    public void StopReadCallBack() {
                        // Implementação opcional
                    }
                });
            }
        }).start();
    }



    private void stopReadingRFID() {
        try {
            Log.d("RFID", "Parando a leitura de tags RFID...");

            readerHelp.StopRead();
        } catch (Exception e) {
            Log.e("RFID", "Erro ao parar leitura de RFID", e);
        }
    }


    private void initRfid() {
        try {
            ReaderParameter parameter = readerHelp.GetInventoryPatameter();
            int readerType = readerHelp.GetReaderType();

            // Verifica se o readerType é válido
            if (readerType == -1) {
                throw new Exception("Falha ao obter o tipo de leitor");
            }

            if (readerType == 33 || readerType == 40 || readerType == 35 || readerType == 55 || readerType == 54) {
                parameter.Session = 1;
            } else if (readerType == 112 || readerType == 113 || readerType == 49) {
                parameter.Session = 254;
            } else if (readerType == 97 || readerType == 99 || readerType == 101 || readerType == 102) {
                parameter.Session = 1;
            } else {
                parameter.Session = 0;
            }
            Toast.makeText(MainActivity.this, "Rfid conectado", Toast.LENGTH_SHORT).show();
            readerHelp.SetInventoryPatameter(parameter);
            Log.d("RFID", "RFID inicializado com sucesso");


        } catch (Exception e) {
            Log.e("RFID", "Erro ao inicializar RFID", e);
            Toast.makeText(MainActivity.this, "Erro ao inicializar o RFID", Toast.LENGTH_SHORT).show();
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void verifyStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // Android 13+
            String[] permissions = {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            };

            // Verificar se as permissões de mídia foram concedidas
            boolean allPermissionsGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (!allPermissionsGranted) {
                // Solicitar permissões de mídia para Android 13+
                ActivityCompat.requestPermissions(this, permissions, REQUEST_MEDIA_PERMISSIONS);
            }
        } else {  // Android 12 e inferior
            String[] permissions = {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };

            // Verificar se as permissões de armazenamento foram concedidas
            boolean allPermissionsGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (!allPermissionsGranted) {
                // Solicitar permissões de armazenamento para Android 12 e inferior
                ActivityCompat.requestPermissions(this, permissions, REQUEST_EXTERNAL_STORAGE);
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_EXTERNAL_STORAGE || requestCode == REQUEST_MEDIA_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissão concedida
                Toast.makeText(this, "Permissão concedida", Toast.LENGTH_SHORT).show();
            } else {
                // Permissão negada
                Toast.makeText(this, "Permissão negada", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initGpio() {
        // Ativa GPIO conforme necessário
        // Substitua pelo método apropriado para o seu caso
        OtgUtils.set53GPIOEnabled(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == 523 && !keyPress) {  // Gatilho (tecla 523) pressionado
            keyPress = true;
            startReadingRFID();  // Inicia a leitura de RFID
            return true;  // Captura o evento
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();  // Fecha a Activity ao pressionar o botão "voltar"
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == 523) {  // Gatilho (tecla 523) solto
            keyPress = false;
            stopReadingRFID();  // Para a leitura de RFID
            isStopThread = true; // Indica que a leitura deve ser parada
            return true;  // Captura o evento
        }
        return super.onKeyUp(keyCode, event);
    }

    private void setOpenScan523(boolean isopen) {
        try {
            DeviceManager deviceManager = new DeviceManager();
            if (isopen) {
                deviceManager.setSettingProperty("persist-persist.sys.rfid.key", "0-");
                deviceManager.setSettingProperty("persist-persist.sys.scan.key", "520-521-522-523-");
            } else {
                deviceManager.setSettingProperty("persist-persist.sys.rfid.key", "0-");
                deviceManager.setSettingProperty("persist-persist.sys.scan.key", "520-521-522-");
            }
        } catch (Exception unused) {
            Log.e("RFID", "Erro ao configurar o scan da tecla 523");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setOpenScan523(false);  // Desabilita a tecla 523 no retorno à Activity
        isStopThread = false;   // Garante que a leitura não está parada
    }

    @Override
    protected void onPause() {
        super.onPause();
        setOpenScan523(true);  // Reativa a tecla 523 ao pausar a Activity
        stopReadingRFID();  // Para a leitura de tags ao pausar
        isStopThread = true;  // Marca que a leitura está parada
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        readerHelp.DisConnect();
        // Desativa GPIO e cancela o registro do BroadcastReceiver
        // Substitua pelo método apropriado para o seu caso
        OtgUtils.set53GPIOEnabled(false);
        unregisterReceiver(mVirtualKeyListenerBroadcastReceiver);
    }

    private static class VirtualKeyListenerBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            if (Objects.equals(intent.getAction(), "android.intent.action.CLOSE_SYSTEM_DIALOGS") && reason != null) {
                String SYSTEM_HOME_KEY = "homekey";
                String SYSTEM_RECENT_APPS = "recentapps";
                if (reason.equals(SYSTEM_HOME_KEY)) {
                    Log.d("RFID", "Press HOME key");
                    // Desativa GPIO
                    // Exemplo: OtgUtils.set53GPIOEnabled(false);
                } else if (reason.equals(SYSTEM_RECENT_APPS)) {
                    Log.d("RFID", "Press RECENT_APPS key");
                    // Ativa GPIO
                    // Exemplo: OtgUtils.set53GPIOEnabled(true);
                }
            }
        }
    }
}