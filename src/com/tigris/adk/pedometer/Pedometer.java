/*
 *  Pedometer - Android App
 *  Copyright (C) 2009 Levente Bagi
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tigris.adk.pedometer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.tigris.adk.pedometer.R;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.CompoundButton;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

public class Pedometer extends Activity {
	private static final String TAG = "Pedometer";
    private SharedPreferences mSettings;
    private PedometerSettings mPedometerSettings;
    private Utils mUtils;
    
    private TextView mStepValueView;
    private TextView mPaceValueView;
    private TextView mDistanceValueView;
    private TextView mSpeedValueView;
    private TextView mCaloriesValueView;
    private TextView mDesiredPaceView;
    private int mStepValue;  
    private int mPaceValue;
    private float mDistanceValue;
    private float mSpeedValue;
    private int mCaloriesValue;
    private float mDesiredPaceOrSpeed;
    private int mMaintain;
    private boolean mIsMetric;
    private float mMaintainInc;
    private boolean mQuitting = false; // Set when user selected Quit from menu, can be used by onPause, onStop, onDestroy

	private static final String ACTION_USB_PERMISSION = 
			"com.tigris.adk.pedometer.USB_PERMISSION";
	
	private ToggleButton btnLed;
	private ToggleButton btnPnt;

	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (ACTION_USB_PERMISSION.equals(action)) {
				    // 사용자에게 Android Accessory Protocol을 구현한 장비가 연결되면
				    // 수락할 것인지 문의한 다이얼로그에 대한 사용자의 선택 결과를 받는다.
					synchronized (this) {
					UsbAccessory accessory = UsbManager.getAccessory(intent);
					if (intent.getBooleanExtra(
							UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						// 수락했을 경우
						showMessage("receiver : USB Host 연결됨.");
					} else {
						Log.d(Pedometer.class.getName(), 
								"permission denied for accessory "
								+ accessory);
					}
					
					openAccessory(accessory);
					// 연결 수락 결과를 받았음을 표시
					mPermissionRequestPending = false;
				}
			} else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
				// Android Accessory Protocol을 구현한 장비의 연결이 해제되었을 때
				UsbAccessory accessory = UsbManager.getAccessory(intent);
				// 앱이 사용하고 있는 장비와 같은 것인지 확인
				if (accessory != null && accessory.equals(mAccessory)) {
					showMessage("USB Host 연결 해제됨.");
					closeAccessory();
				}
			}
		}
	};
	

	public TextView txtMsg;
    private AdkHandler handler;

	private UsbManager mUsbManager;
	private UsbAccessory mAccessory;
	private PendingIntent mPermissionIntent;
	private boolean mPermissionRequestPending;
	
    /**
     * True, when service is running.
     */
    private boolean mIsRunning;
      
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "[ACTIVITY] onCreate");
        super.onCreate(savedInstanceState);
        
        mStepValue = 0;
        mPaceValue = 0;
        
        setContentView(R.layout.main);
        
        mUtils = Utils.getInstance();
        
		txtMsg = (TextView)this.findViewById(R.id.txtMsg);
		btnLed = (ToggleButton)this.findViewById(R.id.btnLed); // Arduino에 임의로 설정된(prefixed) 데이터 출력 
		btnPnt = (ToggleButton)this.findViewById(R.id.btnPnt); // step_value 출력

        //Android Accessory Protocol을 구현한 장비의 연결에 대한 브로드캐스트 리시버 등록
      	IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
      	filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
      	registerReceiver(mUsbReceiver, filter);
      	
      	mUsbManager = UsbManager.getInstance(this);
		mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
				ACTION_USB_PERMISSION), 0);
		
		 btnLed.setOnCheckedChangeListener(new OnCheckedChangeListener(){
             @Override
             public void onCheckedChanged(CompoundButton buttonView,
                                      boolean isChecked) {
                  if(handler != null && handler.isConnected()){
                       handler.write((byte)0x1, (byte)0x0, isChecked ? 1 : 0); // Arduino에 지정된 주소값에 맞춰야 함
                       showMessage("Prefixed Data Print " + (isChecked ? "Started" : "Ends"));
                  }
             }});
		 
		 btnPnt.setOnCheckedChangeListener(new OnCheckedChangeListener(){ 
             @Override
             public void onCheckedChanged(CompoundButton buttonView,  
                                      boolean isChecked) { 
                  if(handler != null && handler.isConnected()){
                		  handler.write((byte)0x1, (byte)0x1, mStepValue);  //전송될 데이터 (스텝 값) 버퍼로 전송
                          showMessage("StepCounter Data Print " + (isChecked ? "Started" : "Ends"));
                  }
             }});
    }
    
    @Override
    protected void onStart() {
        Log.i(TAG, "[ACTIVITY] onStart");
        super.onStart();
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "[ACTIVITY] onResume");
        super.onResume();
        
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        mPedometerSettings = new PedometerSettings(mSettings);
        
        mUtils.setSpeak(mSettings.getBoolean("speak", false));
        
        // Read from preferences if the service was running on the last onPause
        mIsRunning = mPedometerSettings.isServiceRunning();
        
        // Start the service if this is considered to be an application start (last onPause was long ago)
        if (!mIsRunning && mPedometerSettings.isNewStart()) {
            startStepService();
            bindStepService();
        }
        else if (mIsRunning) {
            bindStepService();
        }
        
        mPedometerSettings.clearServiceRunning();

        mStepValueView     = (TextView) findViewById(R.id.step_value);
        mPaceValueView     = (TextView) findViewById(R.id.pace_value);
        mDistanceValueView = (TextView) findViewById(R.id.distance_value);
        mSpeedValueView    = (TextView) findViewById(R.id.speed_value);
        mCaloriesValueView = (TextView) findViewById(R.id.calories_value);
        mDesiredPaceView   = (TextView) findViewById(R.id.desired_pace_value);

        mIsMetric = mPedometerSettings.isMetric();
        ((TextView) findViewById(R.id.distance_units)).setText(getString(
                mIsMetric
                ? R.string.kilometers
                : R.string.miles
        ));
        ((TextView) findViewById(R.id.speed_units)).setText(getString(
                mIsMetric
                ? R.string.kilometers_per_hour
                : R.string.miles_per_hour
        ));
        
        mMaintain = mPedometerSettings.getMaintainOption();
        ((LinearLayout) this.findViewById(R.id.desired_pace_control)).setVisibility(
                mMaintain != PedometerSettings.M_NONE
                ? View.VISIBLE
                : View.GONE
            );
        if (mMaintain == PedometerSettings.M_PACE) {
            mMaintainInc = 5f;
            mDesiredPaceOrSpeed = (float)mPedometerSettings.getDesiredPace();
        }
        else 
        if (mMaintain == PedometerSettings.M_SPEED) {
            mDesiredPaceOrSpeed = mPedometerSettings.getDesiredSpeed();
            mMaintainInc = 0.1f;
        }
        Button button1 = (Button) findViewById(R.id.button_desired_pace_lower);
        button1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mDesiredPaceOrSpeed -= mMaintainInc;
                mDesiredPaceOrSpeed = Math.round(mDesiredPaceOrSpeed * 10) / 10f;
                displayDesiredPaceOrSpeed();
                setDesiredPaceOrSpeed(mDesiredPaceOrSpeed);
            }
        });
        Button button2 = (Button) findViewById(R.id.button_desired_pace_raise);
        button2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mDesiredPaceOrSpeed += mMaintainInc;
                mDesiredPaceOrSpeed = Math.round(mDesiredPaceOrSpeed * 10) / 10f;
                displayDesiredPaceOrSpeed();
                setDesiredPaceOrSpeed(mDesiredPaceOrSpeed);
            }
        });
        if (mMaintain != PedometerSettings.M_NONE) {
            ((TextView) findViewById(R.id.desired_pace_label)).setText(
                    mMaintain == PedometerSettings.M_PACE
                    ? R.string.desired_pace
                    : R.string.desired_speed
            );
        }
        
        
        displayDesiredPaceOrSpeed();
        
        UsbAccessory[] accessories = mUsbManager.getAccessoryList();
		UsbAccessory accessory = (accessories == null ? null : accessories[0]);
		if (accessory != null) { // Android Accessory Protocol를 구현한 장비를 찾았을 경우
			if (mUsbManager.hasPermission(accessory)) {
				showMessage("onResume : USB Host 연결됨.");
				openAccessory(accessory);
			} else {
				synchronized (mUsbReceiver) {
					if (!mPermissionRequestPending) {
						mUsbManager.requestPermission(accessory,
								mPermissionIntent); // USB 연결을 통해 장비에 연결해도 되는지 사용자에게 문의
						mPermissionRequestPending = true; // 연결권한을 물어보드 코드를 실행했음을 표시
					}
				}
			}
		} else {
			Log.d(Pedometer.class.getName(), "mAccessory is null");
		}
    }
    
    private void displayDesiredPaceOrSpeed() {
        if (mMaintain == PedometerSettings.M_PACE) {
            mDesiredPaceView.setText("" + (int)mDesiredPaceOrSpeed);
        }
        else {
            mDesiredPaceView.setText("" + mDesiredPaceOrSpeed);
        }
    }
    
    @Override
    protected void onPause() {
        Log.i(TAG, "[ACTIVITY] onPause");
        if (mIsRunning) {
            unbindStepService();
        }
        if (mQuitting) {
            mPedometerSettings.saveServiceRunningWithNullTimestamp(mIsRunning);
        }
        else {
            mPedometerSettings.saveServiceRunningWithTimestamp(mIsRunning);
        }

        super.onPause();
        savePaceSetting();
        
        closeAccessory();
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "[ACTIVITY] onStop");
        super.onStop();
    }

    protected void onDestroy() {
        Log.i(TAG, "[ACTIVITY] onDestroy");
 		unregisterReceiver(mUsbReceiver);
        super.onDestroy();
    }
    
    private void openAccessory(UsbAccessory accessory){
    	mAccessory = accessory;
        if(handler == null)
             handler = new AdkHandler();

        handler.open(mUsbManager, mAccessory);
	}
	
	private void closeAccessory(){
		if(handler != null && handler.isConnected())
            handler.close();
       mAccessory = null;
	}
	
	private void showMessage(String msg){
		txtMsg.setText(msg);
	}
    protected void onRestart() {
        Log.i(TAG, "[ACTIVITY] onRestart");
        super.onDestroy();
    }

    private void setDesiredPaceOrSpeed(float desiredPaceOrSpeed) {
        if (mService != null) {
            if (mMaintain == PedometerSettings.M_PACE) {
                mService.setDesiredPace((int)desiredPaceOrSpeed);
            }
            else
            if (mMaintain == PedometerSettings.M_SPEED) {
                mService.setDesiredSpeed(desiredPaceOrSpeed);
            }
        }
    }
    
    private void savePaceSetting() {
        mPedometerSettings.savePaceOrSpeedSetting(mMaintain, mDesiredPaceOrSpeed);
    }

    private StepService mService;
    
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = ((StepService.StepBinder)service).getService();

            mService.registerCallback(mCallback);
            mService.reloadSettings();
            
        }

        public void onServiceDisconnected(ComponentName className) {
            mService = null;
        }
    };
    

    private void startStepService() {
        if (! mIsRunning) {
            Log.i(TAG, "[SERVICE] Start");
            mIsRunning = true;
            startService(new Intent(Pedometer.this,
                    StepService.class));
        }
    }
    
    private void bindStepService() {
        Log.i(TAG, "[SERVICE] Bind");
        bindService(new Intent(Pedometer.this, 
                StepService.class), mConnection, Context.BIND_AUTO_CREATE + Context.BIND_DEBUG_UNBIND);
    }

    private void unbindStepService() {
        Log.i(TAG, "[SERVICE] Unbind");
        unbindService(mConnection);
    }
    
    private void stopStepService() {
        Log.i(TAG, "[SERVICE] Stop");
        if (mService != null) {
            Log.i(TAG, "[SERVICE] stopService");
            stopService(new Intent(Pedometer.this,
                  StepService.class));
        }
        mIsRunning = false;
    }
    
    private void resetValues(boolean updateDisplay) {
        if (mService != null && mIsRunning) {
            mService.resetValues();                    
        }
        else {
            mStepValueView.setText("0");
            mPaceValueView.setText("0");
            mDistanceValueView.setText("0");
            mSpeedValueView.setText("0");
            mCaloriesValueView.setText("0");
            SharedPreferences state = getSharedPreferences("state", 0);
            SharedPreferences.Editor stateEditor = state.edit();
            if (updateDisplay) {
                stateEditor.putInt("steps", 0);
                stateEditor.putInt("pace", 0);
                stateEditor.putFloat("distance", 0);
                stateEditor.putFloat("speed", 0);
                stateEditor.putFloat("calories", 0);
                stateEditor.commit();
            }
        }
    }

    private static final int MENU_SETTINGS = 8;
    private static final int MENU_QUIT     = 9;

    private static final int MENU_PAUSE = 1;
    private static final int MENU_RESUME = 2;
    private static final int MENU_RESET = 3;
    
    /* Creates the menu items */
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        if (mIsRunning) {
            menu.add(0, MENU_PAUSE, 0, R.string.pause)
            .setIcon(android.R.drawable.ic_media_pause)
            .setShortcut('1', 'p');
        }
        else {
            menu.add(0, MENU_RESUME, 0, R.string.resume)
            .setIcon(android.R.drawable.ic_media_play)
            .setShortcut('1', 'p');
        }
        menu.add(0, MENU_RESET, 0, R.string.reset)
        .setIcon(android.R.drawable.ic_menu_close_clear_cancel)
        .setShortcut('2', 'r');
        menu.add(0, MENU_SETTINGS, 0, R.string.settings)
        .setIcon(android.R.drawable.ic_menu_preferences)
        .setShortcut('8', 's')
        .setIntent(new Intent(this, Settings.class));
        menu.add(0, MENU_QUIT, 0, R.string.quit)
        .setIcon(android.R.drawable.ic_lock_power_off)
        .setShortcut('9', 'q');
        return true;
    }

    /* Handles item selections */
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_PAUSE:
                unbindStepService();
                stopStepService();
                return true;
            case MENU_RESUME:
                startStepService();
                bindStepService();
                return true;
            case MENU_RESET:
                resetValues(true);
                return true;
            case MENU_QUIT:
                resetValues(false);
                unbindStepService();
                stopStepService();
                mQuitting = true;
                finish();
                return true;
        }
        return false;
    }
 
    // TODO: unite all into 1 type of message
    private StepService.ICallback mCallback = new StepService.ICallback() {
        public void stepsChanged(int value) {
            mHandler.sendMessage(mHandler.obtainMessage(STEPS_MSG, value, 0));
        }
        public void paceChanged(int value) {
            mHandler.sendMessage(mHandler.obtainMessage(PACE_MSG, value, 0));
        }
        public void distanceChanged(float value) {
            mHandler.sendMessage(mHandler.obtainMessage(DISTANCE_MSG, (int)(value*1000), 0));
        }
        public void speedChanged(float value) {
            mHandler.sendMessage(mHandler.obtainMessage(SPEED_MSG, (int)(value*1000), 0));
        }
        public void caloriesChanged(float value) {
            mHandler.sendMessage(mHandler.obtainMessage(CALORIES_MSG, (int)(value), 0));
        }
    };
    
    private static final int STEPS_MSG = 1;
    private static final int PACE_MSG = 2;
    private static final int DISTANCE_MSG = 3;
    private static final int SPEED_MSG = 4;
    private static final int CALORIES_MSG = 5;
    
    public Handler mHandler = new Handler() { // what_if public?
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case STEPS_MSG:
                    mStepValue = (int)msg.arg1; 
                    mStepValueView.setText("" + mStepValue); // 값을 증가시킨다.
                    break;
                case PACE_MSG:
                    mPaceValue = msg.arg1;
                    if (mPaceValue <= 0) { 
                        mPaceValueView.setText("0");
                    }
                    else {
                        mPaceValueView.setText("" + (int)mPaceValue);
                    }
                    break;
                case DISTANCE_MSG:
                    mDistanceValue = ((int)msg.arg1)/1000f;
                    if (mDistanceValue <= 0) { 
                        mDistanceValueView.setText("0");
                    }
                    else {
                        mDistanceValueView.setText(
                                ("" + (mDistanceValue + 0.000001f)).substring(0, 5)
                        );
                    }
                    break;
                case SPEED_MSG:
                    mSpeedValue = ((int)msg.arg1)/1000f;
                    if (mSpeedValue <= 0) { 
                        mSpeedValueView.setText("0");
                    }
                    else {
                        mSpeedValueView.setText(
                                ("" + (mSpeedValue + 0.000001f)).substring(0, 4)
                        );
                    }
                    break;
                case CALORIES_MSG:
                    mCaloriesValue = msg.arg1;
                    if (mCaloriesValue <= 0) { 
                        mCaloriesValueView.setText("0");
                    }
                    else {
                        mCaloriesValueView.setText("" + (int)mCaloriesValue);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
        
    };
    

}


