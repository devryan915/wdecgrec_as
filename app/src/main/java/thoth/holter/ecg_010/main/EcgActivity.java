package thoth.holter.ecg_010.main;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import com.broadchance.ecgview.ECGGLSurfaceView;
import com.broadchance.ecgview.ECGGLSurfaceView.EcgLevel;
import com.broadchance.ecgview.ECGGLSurfaceView.EcgType;
import com.broadchance.entity.UIUserInfoLogin;
import com.broadchance.utils.BleDataUtil;
import com.broadchance.utils.CommonUtil;
import com.broadchance.utils.ConstantConfig;
import com.broadchance.utils.FilterUtil;
import com.broadchance.utils.LogUtil;
import com.broadchance.utils.UIUtil;
import com.broadchance.wdecgrec.BaseActivity;
import thoth.holter.ecg_010.R;
import thoth.holter.ecg_010.manager.DataManager;
import thoth.holter.ecg_010.manager.FrameDataMachine;
import thoth.holter.ecg_010.manager.PlayerManager;
import thoth.holter.ecg_010.manager.SettingsManager;
import thoth.holter.ecg_010.services.BleDataParserService;
import thoth.holter.ecg_010.services.BleDomainService;
import thoth.holter.ecg_010.services.BluetoothLeService;
import thoth.holter.ecg_010.services.GuardService;

@SuppressLint("HandlerLeak")
public class EcgActivity extends BaseActivity {
	/**
	 * 第一通道
	 */
	private ECGGLSurfaceView ecgGLSurfaceViewChannelMII;
	// private RadioGroup rgEcgSpeed;
	// private RadioGroup rgEcgRage;
	/**
	 * 当前速度
	 */
	private View rbSpeed;
	/**
	 * 当前幅度
	 */
	private View rbRage;
	private TextView ecg_curhearrate;
	private TextView ecg_curspeedvalue;
	private TextView tvCurDate;
	private TextView tvCurTime;
	private TextView tvRunTime;
	private View buttonTitleBack;

	private final static int GRID_VNUM_20 = 20;
	private final static int GRID_VNUM_30 = 30;
	private final static int GRID_VNUM_60 = 60;
	public static final int UPDATE_MIICANVAS = 1;
	// private int mEcgMode = ECGGLSurfaceView.ECG_MODE_LOW;

	private LinkedBlockingQueue<Integer> miiQueue = new LinkedBlockingQueue<Integer>();
	long totalReceivePoints = 0;
	private ScheduledExecutorService executor;
	private ExecutorService executorReceive;
	protected static final String TAG = EcgActivity.class.getSimpleName();
	public static EcgActivity Instance;
	/**
	 * 心率
	 */
	private int hearRate = 0;
	// 上一次点击脱落时间
	long lastMIIOffTime = 0;
	// private FilterUtil filter = FilterUtil.Instance;
	private boolean isStopMII = false;
	// private Object handlerLock = new Object();
	private Handler handler = new Handler() {
		@Override
		public void handleMessage(android.os.Message msg) {
			// synchronized (handlerLock) {
			try {
				if (msg.what == UPDATE_MIICANVAS) {
					EcgData data = (EcgData) msg.obj;
					// ecgGLSurfaceViewChannelMII.drawECG(data.queueArray,
					// data.pointNumber);
					ecgGLSurfaceViewChannelMII.drawECG(data.queueArray);
					// setHeartRate();
				}
			} catch (Exception e) {
				LogUtil.e(TAG, e);
			}
			// }
		}
	};
	private SimpleDateFormat sdf = new SimpleDateFormat(
			"yyyy-MM-ddHH:mm:ss.SSS");
	private Handler handlerTime = new Handler() {
		@Override
		public void handleMessage(android.os.Message msg) {
			if (msg.what == BleDomainService.MSG_SET_HEART) {
				hearRate = msg.getData().getInt("heart");
				if (ConstantConfig.Debug) {
					ecg_curhearrate.setText(hearRate + "");
					if (runTime != null) {
						long time = new Date().getTime() - runTime.getTime();
						int hours = (int) (time / (1000 * 60 * 60));
						int min = (int) (time % (1000 * 60 * 60) / (1000 * 60));
						int sec = (int) (time % (1000 * 60 * 60) % (1000 * 60) / 1000);
						tvRunTime.setText(hours + ":" + min + ":" + sec);
					}
				} else {
					if (hearRate >= ConstantConfig.Alert_HR_Down
							&& hearRate <= ConstantConfig.Alert_HR_Up) {
						ecg_curhearrate.setText(hearRate + "");
					} else {
						// ecg_curhearrate.setText("0  ");
						if (ConstantConfig.Debug) {
							LogUtil.w(TAG, "当前心率" + hearRate);
						}
						ecg_curhearrate.setText("-");
					}
				}
			}
		}
	};

	private Messenger mMesg = new Messenger(handlerTime);
	public static long count = 0;
	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, final Intent intent) {
			final String action = intent.getAction();
			if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				miiQueue.clear();
			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED
					.equals(action)) {
				if (ConstantConfig.Debug) {
					ecgGLSurfaceViewChannelMII.clearDraw();
				}
			} else if (BleDataParserService.ACTION_ECGMII_DATA_AVAILABLE
					.equals(action)) {
				synchronized (miiQueue) {
					int[] ecgData = intent
							.getIntArrayExtra(BluetoothLeService.EXTRA_DATA);
					receiveEcgData(R.id.ecgGLSurfaceViewChannelMII, ecgData);
				}
				// if (ConstantConfig.Debug) {
				// Log.d(ConstantConfig.DebugTAG,
				// TAG + "\nmiiQueue:" + miiQueue.size());
				// }
			} else if (BleDataParserService.ACTION_ECGMV1_DATA_AVAILABLE
					.equals(action)) {
				// synchronized (mv1Queue) {
				// int[] ecgData = intent
				// .getIntArrayExtra(BluetoothLeService.EXTRA_DATA);
				// receiveEcgData(R.id.ecgGLSurfaceViewChannelMV1, ecgData);
				// }
			} else if (BleDataParserService.ACTION_ECGMV5_DATA_AVAILABLE
					.equals(action)) {
				// executorReceive.execute(new Runnable() {
				// @Override
				// public void run() {
				// synchronized (mv5Queue) {
				// int[] ecgData = intent
				// .getIntArrayExtra(BluetoothLeService.EXTRA_DATA);
				// receiveEcgData(R.id.ecgGLSurfaceViewChannelMV5,
				// ecgData);
				// }
				// }
				// });

			} else if (FrameDataMachine.ACTION_ECGMII_DATAOFF_AVAILABLE
					.equals(action)) {
				if (System.currentTimeMillis() - lastMIIOffTime > 5000) {
					PlayerManager.getInstance().playDevFallOff();
					showToast("MⅡ电极脱落");
					lastMIIOffTime = System.currentTimeMillis();
				}
			} else if (FrameDataMachine.ACTION_ECGMV1_DATAOFF_AVAILABLE
					.equals(action)) {
				PlayerManager.getInstance().playDevFallOff();
				showToast("MV1电极脱落");
			} else if (FrameDataMachine.ACTION_ECGMV5_DATAOFF_AVAILABLE
					.equals(action)) {
				PlayerManager.getInstance().playDevFallOff();
				showToast("MV5电极脱落");
			}
		}
	};

	private void receiveEcgData(int viewID, int[] ecg) {
		int totalEcg;
		Queue<Integer> queue = null;
		switch (viewID) {
		case R.id.ecgGLSurfaceViewChannelMII:
			totalEcg = ecgGLSurfaceViewChannelMII.getCurrTotalPointNumber();
			queue = miiQueue;
			break;
		default:
			return;
		}
		if (ecg != null) {
			for (int i = 0; i < ecg.length; i++) {
				try {
					Integer data = ecg[i];
					queue.offer(data);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			int length = queue.size();

			// 防止队列无限增大，检查队列是否超过最大允许上限，如果超出则移除老数据
			// int maxLength = totalEcg * 3;
			int maxLength = (int) Math.max(10000, totalEcg * 4f);
			if (length > maxLength) {
				length = (int) (totalEcg * 0.5f);
				if (ConstantConfig.Debug) {
					LogUtil.d(TAG, "超过最大" + maxLength + " 抛掉" + length);
				}
				// 删除一个周期保留三个周期缓冲
				for (int j = 0; j < length; j++) {
					queue.poll();
				}
			}
		}
	}

	class EcgData {
		public Integer[] queueArray;
		public int pointNumber;
	}

	class DrawEcgData {
		/**
		 * 第一次画图时间
		 */
		public long firtTime;
		/**
		 * 上一次画图时间
		 */
		// public Long lastTime;
		/**
		 * 从第一次画图产生的点数
		 */
		public long totalCount;
		public boolean isEnough;
	}

	DrawEcgData dedMii;

	// DrawEcgData dedMv1;
	private void drawEcgData(int viewID) {
		// long useTime = System.currentTimeMillis();
		ECGGLSurfaceView ecgglSurfaceView = null;
		Queue<Integer> queue = null;
		int action;
		// long msdif = 0;
		int pointNumber = 0;
		DrawEcgData ded;
		switch (viewID) {
		case R.id.ecgGLSurfaceViewChannelMII:
			if (isStopMII)
				return;
			ecgglSurfaceView = ecgGLSurfaceViewChannelMII;
			queue = miiQueue;
			ded = dedMii;
			action = UPDATE_MIICANVAS;
			// setHeartRate();
			break;
		default:
			return;
		}
		if (!ded.isEnough
				&& queue.size() < ecgglSurfaceView.getCurrTotalPointNumber() * 1.5f) {
			// 缓冲一般的数据来保证画图平滑
			// if (dedMii == null) {
			// dedMii = new DrawEcgData();
			// }
			return;
		}
		if (ded.firtTime < 1) {
			ded.firtTime = System.currentTimeMillis();
			return;
		}
		// 计算应该画图的总数
		long total = (long) ((System.currentTimeMillis() - ded.firtTime)
				* FrameDataMachine.FRAME_DOTS_FREQUENCY_FILTER * 0.001f);
		pointNumber = (int) (total - ded.totalCount);
		pointNumber = Math.min(pointNumber, queue.size());
		LogUtil.d(TAG, "ctotal:" + total + " totalCount:" + ded.totalCount
				+ " queue:" + queue.size() + " pointNumber:" + pointNumber);
		ded.totalCount += pointNumber;
		if (pointNumber < 1) {
			// 不够画图
			ded.isEnough = false;
			return;
		}
		ded.isEnough = true;
		Integer[] queueArray = new Integer[pointNumber];
		for (int j = 0; j < pointNumber; j++) {
			queueArray[j] = queue.poll();
		}
		EcgData data = new EcgData();
		data.queueArray = queueArray;
		UIUtil.setMessage(handler, action, data);
	}

	private void setHeartRate() {
		handlerTime.postDelayed(new Runnable() {
			@Override
			public void run() {
				// 小于20大于200的异常心率值，过滤一下，让不要显示
				// if (GuardService.Instance != null)
				// GuardService.Instance.getHeartRate(mMesg);

				hearRate = FilterUtil.Instance.getHeartRate();
				if (ConstantConfig.Debug) {
					ecg_curhearrate.setText(hearRate + "");
					if (runTime != null) {
						long time = new Date().getTime() - runTime.getTime();
						int hours = (int) (time / (1000 * 60 * 60));
						int min = (int) (time % (1000 * 60 * 60) / (1000 * 60));
						int sec = (int) (time % (1000 * 60 * 60) % (1000 * 60) / 1000);
						// tvRunTime.setText(hours + ":" + min + ":" + sec);
						tvRunTime.setText((Integer.parseInt(CommonUtil
								.getCurCpuFreq()) / 1000f) + "MHZ ");
					}
				} else {
					if (hearRate >= ConstantConfig.Alert_HR_Down
							&& hearRate <= ConstantConfig.Alert_HR_Up) {
						ecg_curhearrate.setText(hearRate + "");
					} else {
						// ecg_curhearrate.setText("0  ");
						if (ConstantConfig.Debug) {
							LogUtil.w(TAG, "当前心率" + hearRate);
						}
						ecg_curhearrate.setText("-");
					}
				}

				setHeartRate();
			}
		}, 2000);
	}

	private Date runTime;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_ecg);
		Instance = this;
		runTime = CommonUtil.getDate();
		// executorReceive = Executors.newCachedThreadPool();
		ecgGLSurfaceViewChannelMII = (ECGGLSurfaceView) findViewById(R.id.ecgGLSurfaceViewChannelMII);
		// 修改为限定单通道
		ecgGLSurfaceViewChannelMII.initView(EcgActivity.this, GRID_VNUM_30);
		ecgGLSurfaceViewChannelMII.setOnClickListener(EcgActivity.this);
		ecg_curhearrate = (TextView) findViewById(R.id.ecg_curhearrate);
		ecg_curspeedvalue = (TextView) findViewById(R.id.ecg_curspeedvalue);
		buttonTitleBack = (View) findViewById(R.id.buttonTitleBack);
		buttonTitleBack.setOnClickListener(this);
		findViewById(R.id.ecgSpeedLevel1).setOnClickListener(this);
		findViewById(R.id.ecgSpeedLevel2).setOnClickListener(this);
		findViewById(R.id.ecgSpeedLevel3).setOnClickListener(this);
		findViewById(R.id.ecgRangeLevel1).setOnClickListener(this);
		findViewById(R.id.ecgRangeLevel2).setOnClickListener(this);
		findViewById(R.id.ecgRangeLevel3).setOnClickListener(this);
		findViewById(R.id.ecgRangeLevel4).setOnClickListener(this);
		tvCurDate = (TextView) findViewById(R.id.tvCurDate);
		tvCurTime = (TextView) findViewById(R.id.tvCurTime);
		tvRunTime = (TextView) findViewById(R.id.tvRunTime);
		findViewById(R.id.lbl2).setVisibility(View.INVISIBLE);
		tvRunTime.setVisibility(View.INVISIBLE);

		setDefault();
		// UIUserInfoLogin user = DataManager.getUserInfo();
		if (DataManager.isLogin()) {
			String deviceNumber = DataManager.getUserInfo().getMacAddress();
			((TextView) findViewById(R.id.tvMacAddress)).setText(deviceNumber);
		}
		if (ConstantConfig.Debug) {
			View ecg_heartrate = null;
			ecg_heartrate = findViewById(R.id.ecg_curhearrate);
			ecg_heartrate.setOnClickListener(new OnClickListener() {

				@Override
				public void onClick(View v) {
					LayoutInflater inflater = (LayoutInflater) EcgActivity.this
							.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
					LinearLayout layout = (LinearLayout) inflater.inflate(
							R.layout.dialog_input_test, null);
					final EditText editTextValue = (EditText) layout
							.findViewById(R.id.editTextValue);
					editTextValue.setText(ECGGLSurfaceView.BASEFACTOR + "");
					final EditText editTextGridXValue = (EditText) layout
							.findViewById(R.id.editTextGridXValue);
					editTextGridXValue.setText(Integer
							.toHexString(ECGGLSurfaceView.gridLightColor));
					final EditText editTextGridDValue = (EditText) layout
							.findViewById(R.id.editTextGridDValue);
					editTextGridDValue.setText(Integer
							.toHexString(ECGGLSurfaceView.gridDarkColor));
					final EditText editTextECGValue = (EditText) layout
							.findViewById(R.id.editTextECGValue);
					editTextECGValue.setText(Integer
							.toHexString(ECGGLSurfaceView.ecgLineColor));

					float xDPI = SettingsManager.getInstance().getDpiConfigX();
					float yDPI = SettingsManager.getInstance().getDpiConfigY();
					DisplayMetrics outMetrics = new DisplayMetrics();
					getWindowManager().getDefaultDisplay().getRealMetrics(
							outMetrics);
					xDPI = xDPI <= 0 ? outMetrics.xdpi : xDPI;
					yDPI = yDPI <= 0 ? outMetrics.ydpi : yDPI;

					final EditText editTextXDPI = (EditText) layout
							.findViewById(R.id.editTextXDPI);
					editTextXDPI.setText(xDPI + "");
					final EditText editTextYDPI = (EditText) layout
							.findViewById(R.id.editTextYDPI);
					editTextYDPI.setText(yDPI + "");
					final EditText editTextURL = (EditText) layout
							.findViewById(R.id.editTextURL);
					editTextURL.setText("" + ConstantConfig.SERVER_URL);

					Button buttonOK = (Button) layout
							.findViewById(R.id.buttonOK);
					final Dialog dialog = UIUtil.buildDialog(EcgActivity.this,
							layout);
					buttonOK.setOnClickListener(new OnClickListener() {
						@Override
						public void onClick(View v) {
							try {
								ECGGLSurfaceView.BASEFACTOR = Float
										.parseFloat(editTextValue.getText()
												.toString());
								ECGGLSurfaceView.gridLightColor = Color
										.parseColor("#"
												+ editTextGridXValue.getText()
														.toString().trim());
								ECGGLSurfaceView.gridDarkColor = Color
										.parseColor("#"
												+ editTextGridDValue.getText()
														.toString().trim());
								ECGGLSurfaceView.ecgLineColor = Color
										.parseColor("#"
												+ editTextECGValue.getText()
														.toString().trim());
								SettingsManager.getInstance().setDpiConfigX(
										Float.parseFloat(editTextXDPI.getText()
												.toString()));
								SettingsManager.getInstance().setDpiConfigY(
										Float.parseFloat(editTextYDPI.getText()
												.toString()));
								SettingsManager.getInstance().setServerURL(
										editTextURL.getText().toString());
								ecgGLSurfaceViewChannelMII.initView(
										EcgActivity.this, GRID_VNUM_30);
								ecgGLSurfaceViewChannelMII.setEcgMode(
										EcgType.Range, EcgLevel.Level2);
								ecgGLSurfaceViewChannelMII.requestRender();
								dialog.cancel();
								dialog.dismiss();
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					});
					dialog.show();
				}
			});
		}
		TextView textViewUseName = (TextView) findViewById(R.id.textViewUseName);
		textViewUseName.setText(DataManager.getUserInfo().getNickName());
	}

	private void setDefault() {
		rbSpeed = findViewById(R.id.ecgSpeedLevel2);
		rbRage = findViewById(R.id.ecgRangeLevel2);
		onClick(rbSpeed);
		onClick(rbRage);
	}

	private void setEcgMode(EcgType type, EcgLevel level) {
		ecgGLSurfaceViewChannelMII.setEcgMode(type, level);
		// miiQueue.clear();
		// filter.resetFilter();
	}

	/**
	 * 
	 * @param viewID
	 */

	// private Object executorLock = new Object();
	private AtomicBoolean atomicBooleanDraw = new AtomicBoolean(false);

	// private AtomicBoolean atomicBooleanDrawMII = new AtomicBoolean(false);
	// private AtomicBoolean atomicBooleanDrawMV1 = new AtomicBoolean(false);
	// private AtomicBoolean atomicBooleanDrawMV5 = new AtomicBoolean(false);

	private void startExecutor() {
		final int drawFreq = 40;
		executor = Executors.newScheduledThreadPool(3);
		executor.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				if (atomicBooleanDraw.compareAndSet(false, true)) {
					try {
						drawEcgData(R.id.ecgGLSurfaceViewChannelMII);
					} catch (Exception e) {
						if (ConstantConfig.Debug) {
							LogUtil.e(TAG, e);
						}
					} finally {
						atomicBooleanDraw.set(false);

					}
				} else {

				}
			}
		}, 0, drawFreq, TimeUnit.MILLISECONDS);
		// executor.scheduleWithFixedDelay(new Runnable() {
		// @Override
		// public void run() {
		// UIUtil.setMessage(handlerTime, 0);
		// }
		// }, 0, 111, TimeUnit.MILLISECONDS);
		setTime();
		setHeartRate();
		// executor.scheduleWithFixedDelay(new Runnable() {
		// @Override
		// public void run() {
		// UIUtil.setMessage(handlerTime, 1);
		// }
		// }, 0, 1000, TimeUnit.MILLISECONDS);
	}

	private void setTime() {
		handlerTime.postDelayed(new Runnable() {

			@Override
			public void run() {
				String dStr = sdf.format(Calendar.getInstance().getTime());
				tvCurDate.setText(dStr.subSequence(0, 10));
				tvCurTime.setText(dStr.subSequence(10, dStr.length()));
				setTime();
			}
		}, 111);
	}

	private void endExecutor() {
		if (executor != null) {
			executor.shutdown();
		}
	}

	private IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter
				.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter
				.addAction(BleDataParserService.ACTION_ECGMII_DATA_AVAILABLE);
		intentFilter
				.addAction(FrameDataMachine.ACTION_ECGMII_DATAOFF_AVAILABLE);
		return intentFilter;
	}

	@Override
	protected void onResume() {
		super.onResume();
		dedMii = new DrawEcgData();
		// startBleService();
		ecgGLSurfaceViewChannelMII.onResume();
		startExecutor();
		registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
		if (ConstantConfig.Debug) {
			findViewById(R.id.lbl2).setVisibility(View.VISIBLE);
			tvRunTime.setVisibility(View.VISIBLE);
		} else {
			findViewById(R.id.lbl2).setVisibility(View.INVISIBLE);
			tvRunTime.setVisibility(View.INVISIBLE);
		}
	}

	@Override
	protected void onDestroy() {
		if (executorReceive != null) {
			executorReceive.shutdown();
		}
		Instance = null;
		LogUtil.e(TAG, "onDestroy");
		super.onDestroy();
	}

	@Override
	protected void onPause() {
		super.onPause();
		ecgGLSurfaceViewChannelMII.onPause();
		unregisterReceiver(mGattUpdateReceiver);
		endExecutor();
		dedMii = null;
		// stopAlarm();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			returnModeAcitivity();
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	private void returnModeAcitivity() {
		Intent myIntent = new Intent();
		myIntent = new Intent(EcgActivity.this, ModeActivity.class);
		startActivity(myIntent);
		this.finish();
		Runtime.getRuntime().gc();
	}

	private void setSpeed(View v) {
		if (rbSpeed != null) {
			rbSpeed.setBackground(null);
		}
		rbSpeed = v;
		rbSpeed.setBackgroundResource(R.drawable.ecg_radio_bg);
		ecg_curspeedvalue.setText("当前速度：" + ((RadioButton) rbSpeed).getText()
				+ "mm/s,幅频：" + ((RadioButton) rbRage).getText() + "mV/mm");
	}

	private void setRange(View v) {
		if (rbRage != null) {
			rbRage.setBackground(null);
		}
		rbRage = v;
		rbRage.setBackgroundResource(R.drawable.ecg_radio_bg);
		ecg_curspeedvalue.setText("当前速度：" + ((RadioButton) rbSpeed).getText()
				+ "mm/s,幅频：" + ((RadioButton) rbRage).getText() + "mV/mm");
	}

	@Override
	public void onClick(View v) {
		super.onClick(v);
		switch (v.getId()) {
		case R.id.buttonTitleBack:
			returnModeAcitivity();
			break;
		case R.id.ecgGLSurfaceViewChannelMII:
			isStopMII = !isStopMII;
			showToast(isStopMII ? "停止画图" : "开始画图");
			break;
		case R.id.ecgSpeedLevel1:
			setSpeed(v);
			setEcgMode(EcgType.Speed, EcgLevel.Level1);
			break;
		case R.id.ecgSpeedLevel2:
			setSpeed(v);
			setEcgMode(EcgType.Speed, EcgLevel.Level2);
			break;
		case R.id.ecgSpeedLevel3:
			setSpeed(v);
			setEcgMode(EcgType.Speed, EcgLevel.Level3);
			break;
		case R.id.ecgRangeLevel1:
			setRange(v);
			setEcgMode(EcgType.Range, EcgLevel.Level1);
			break;
		case R.id.ecgRangeLevel2:
			setRange(v);
			setEcgMode(EcgType.Range, EcgLevel.Level2);
			break;
		case R.id.ecgRangeLevel3:
			setRange(v);
			setEcgMode(EcgType.Range, EcgLevel.Level3);
			break;
		case R.id.ecgRangeLevel4:
			setRange(v);
			setEcgMode(EcgType.Range, EcgLevel.Level4);
			break;
		default:
			break;
		}
	}
}
