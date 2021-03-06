/**
 *
 */
package thoth.holter.ecg_010.services;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import thoth.holter.ecg_010.manager.DataManager;
import thoth.holter.ecg_010.manager.FrameDataMachine;
import thoth.holter.ecg_010.manager.PreferencesManager;
import thoth.holter.ecg_010.manager.SettingsManager;
import thoth.holter.ecg_010.receiver.PowerChangeReceiver;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

import com.broadchance.entity.FileFrameData;
import com.broadchance.entity.FileType;
import com.broadchance.entity.UIUserInfoLogin;
import com.broadchance.entity.UploadFile;
import com.broadchance.entity.UploadFileResponse;
import com.broadchance.entity.UploadFileStatus;
import com.broadchance.entity.UploadWay;
import com.broadchance.entity.serverentity.ResponseCode;
import com.broadchance.entity.serverentity.UIDevice;
import com.broadchance.entity.serverentity.UIDeviceResponseList;
import com.broadchance.utils.AESEncryptor;
import com.broadchance.utils.ClientGameService;
import com.broadchance.utils.CommonUtil;
import com.broadchance.utils.ConstantConfig;
import com.broadchance.utils.FileUtil;
import com.broadchance.utils.FilterUtil;
import com.broadchance.utils.LogUtil;
import com.broadchance.utils.SSXLXService;
import com.broadchance.utils.UIUtil;
import com.broadchance.utils.ZipUtil;
import com.broadchance.wdecgrec.HttpReqCallBack;

/**
 * @author ryan.wang
 */
public class BleDomainService extends Service {
    private static final String TAG = BleDomainService.class.getSimpleName();
    private PowerChangeReceiver batteryReceiver = new PowerChangeReceiver();
    public final static String ACTION_UPLOAD_STARTREALMODE = ConstantConfig.ACTION_PREFIX
            + "ACTION_UPLOAD_STARTREALMODE";
    public final static String ACTION_UPLOAD_ENDREALMODE = ConstantConfig.ACTION_PREFIX
            + "ACTION_UPLOAD_ENDREALMODE";

    public final static String ACTION_UPLOAD_STARTONEKEYMODE = ConstantConfig.ACTION_PREFIX
            + "ACTION_UPLOAD_STARTONEKEYMODE";
    public final static String ACTION_UPLOAD_ENDONEKEYMODE = ConstantConfig.ACTION_PREFIX
            + "ACTION_UPLOAD_ENDONEKEYMODE";

    public final static String ACTION_UPLOAD_UPLOADCHANGED = ConstantConfig.ACTION_PREFIX
            + "ACTION_UPLOAD_UPLOADCHANGED";
    public static BleDomainService Instance;
    // private final static int Upload_EcgData = 480;
    // private final static int Upload_BreathData = Upload_EcgData / 5;

    // private Timer pkgFrameDataTimer = new Timer();
    // private TimerTask pkgFrameDataTask;

    // private ScheduledExecutorService executor = Executors
    // .newScheduledThreadPool(3);
    // private ScheduledFuture<?> mFuture = null;
    // AtomicBoolean atomicBooleanExecutor = new AtomicBoolean(false);

    // private AtomicBoolean isRealMode = new AtomicBoolean(false);
    // private boolean isOneKeyMode = false;
    private AtomicBoolean isUploading = new AtomicBoolean(false);
    private Object objLock = new Object();
    // private Object objLockUpload = new Object();
    private String msg;
    private int uploadedFileRetryTimes;
    List<UploadFile> waitUploadFiles;
    /**
     * 正在进行的上传方式
     */
    UploadWay curUploadWay;
    /**
     * 即将进行的上传方式
     */
    UploadWay nUploadWay = UploadWay.Batch;
    /**
     * 限定每次打包个数
     */
    private final static int LIMIT_UPLOADBATCH_COUNT = 5;
    /**
     * 上传尝试次数
     */
    private final static int UPLOAD_RETRYTIMES = 3;

    // private long lastRealUpTime = System.currentTimeMillis();

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BleDomainService.ACTION_UPLOAD_STARTREALMODE.equals(action)) {
                FrameDataMachine.getInstance().startRealTimeMode();
                // isRealMode.compareAndSet(false, true);
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, "开启实时上传");
                    UIUtil.showRemoteToast("开启实时上传");
                }
                // startRealTimeMode();
            } else if (BleDomainService.ACTION_UPLOAD_ENDREALMODE
                    .equals(action)) {
                // isRealMode.compareAndSet(true, false);
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, "结束实时上传");
                    UIUtil.showRemoteToast("结束实时上传");
                }
                FrameDataMachine.getInstance().endRealTimeMode();
            } else if (BleDomainService.ACTION_UPLOAD_STARTONEKEYMODE
                    .equals(action)) {
                if (nUploadWay != UploadWay.OneKey) {
                    nUploadWay = UploadWay.OneKey;
                    if (ConstantConfig.Debug) {
                        LogUtil.d(TAG, "开启一键上传");
                    }
                    new AsyncTask<Void, Void, String>() {
                        @Override
                        protected void onPostExecute(String result) {
                            startUpload();
                            super.onPostExecute(result);
                        }

                        @Override
                        protected String doInBackground(Void... params) {
                            initOneKeyUpload();
                            return null;
                        }

                    }.execute();

                }
            } else if (BleDomainService.ACTION_UPLOAD_ENDONEKEYMODE
                    .equals(action)) {
                nUploadWay = UploadWay.Batch;
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, "结束一键上传");
                }
            }
        }
    };

    /**
     * 初始化一键上传，扫描目录，并同步数据库
     */
    private void initOneKeyUpload() {
        String dir = FileUtil.getEcgDir();
        if (dir == null) {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, "ecg目录不存在:" + dir);
            }
            return;
        }
        File ecgDir = new File(dir);
        File[] files = ecgDir.listFiles();
        if (files != null) {
            int count = 0;
            for (File file : files) {
                if (!DataManager.isUploadFileExist(file.getName())) {
                    // TODO
                    DataManager.saveUploadFile(file.getName(),
                            file.getAbsolutePath(), CommonUtil.getDate(),
                            CommonUtil.getDate(), FileType.Supplement, "", "");
                    count++;
                }
            }
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, "从本地恢复:" + count + "到数据库");
            }
        }
    }

    /**
     * 根据时间抓取数据
     *
     * @param date
     * @param limit
     * @return
     */
    private List<UploadFile> getUploadFile(Date date, int limit) {
        synchronized (objLock) {
            List<UploadFile> uploadFiles = DataManager.getUploadFile(date,
                    limit);
            boolean retBoolean = DataManager.updateUploadFileStatus(
                    uploadFiles, UploadFileStatus.Uploading);
            return retBoolean ? uploadFiles : null;
        }
    }

    private int getCountByStatus(UploadFileStatus status) {
        int count = 0;
        if (waitUploadFiles == null)
            return count;
        for (UploadFile uploadedFile : waitUploadFiles) {
            if (uploadedFile.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * 将上传中的文件重置状态
     *
     * @param status 限定UploadFileStatus.UploadFailed UploadFileStatus.Uploaded
     */
    private void setUploadByStatus(UploadFileStatus status) {
        setUploadByStatus(waitUploadFiles, status);
    }

    private void setUploadByStatus(List<UploadFile> uploadFiles,
                                   UploadFileStatus status) {
        if (uploadFiles == null)
            return;
        for (UploadFile uploadedFile : uploadFiles) {
            if (uploadedFile.getStatus() == UploadFileStatus.Uploading) {
                uploadedFile.setStatus(status);
            }
        }

    }

    private void sendUploadBroadCast(int uploadedCount, int totalCount) {
        int count = getCountByStatus(UploadFileStatus.Uploaded);
        int totalWaitUpload = waitUploadFiles != null ? waitUploadFiles.size()
                : 0;
        Intent intent = new Intent(ACTION_UPLOAD_UPLOADCHANGED);
        intent.putExtra(BluetoothLeService.EXTRA_DATA, uploadedCount + ":"
                + totalCount);
        sendBroadcast(intent);
    }

    private void uploadECGFile() {
        try {
            List<File> files = null;
            // List<UpLoadData> upLoadDatas = null;
            JSONArray upLoadDatas = new JSONArray();
            String zipPath = FileUtil.ECG_BATCH_UPLOADDIR + "uploadData.zip";
            // SimpleDateFormat sdf = new
            // SimpleDateFormat("yyyyMMdd HH:mm:ss.SSS");
            // String devID = DataManager.getUserInfo().getMacAddress();
            files = new ArrayList<File>();
            // upLoadDatas = new ArrayList<UpLoadData>();
            int count = 0;
            String starttime = "";
            String endtime = "";
            // JSONArray hrs = new JSONArray();
            // String fileinfo = "";
            for (int i = 0; i < waitUploadFiles.size(); i++) {
                UploadFile uploadFile = waitUploadFiles.get(i);
                if (uploadFile.getStatus() == UploadFileStatus.Uploaded
                        || uploadFile.getStatus() == UploadFileStatus.Uploading) {
                    continue;
                }
                if (count > LIMIT_UPLOADBATCH_COUNT) {
                    break;
                }
                File[] wFiles = new File[]{new File(uploadFile.getPath()),
                        new File(uploadFile.getBpath())};
                for (int j = 0; j < wFiles.length; j++) {
                    File file = wFiles[j];
                    if (file.exists()) {
                        count++;
                        uploadFile.setStatus(UploadFileStatus.Uploading);
                        files.add(file);
                        // UpLoadData data = new UpLoadData();
                        JSONObject data = new JSONObject();
                        String sTime = CommonUtil.getTime_B(uploadFile
                                .getDataBeginTime());
                        String eTime = CommonUtil.getTime_B(uploadFile
                                .getDataEndTime());
                        data.put("starttime", sTime);
                        data.put("endtime", eTime);
                        data.put("filename", file.getName());
                        data.put("datatype", j == 0 ? "1" : "2");
                        // 抓取文件对应ble的期间段
                        if (sTime.compareTo(starttime) < 0
                                || starttime.isEmpty()) {
                            starttime = sTime;
                        }
                        if (eTime.compareTo(endtime) > 0 || endtime.isEmpty()) {
                            endtime = eTime;
                        }
                        if (j == 0) {
                            data.put("hrs", new JSONArray(uploadFile.getHrs()));
                        }
                        upLoadDatas.put(data);
                    } else {
                        if (ConstantConfig.Debug) {
                            LogUtil.d(
                                    TAG,
                                    "数据库中存在此文件，但是文件目录中找不到："
                                            + uploadFile.getFileName());
                        }
                        uploadFile.setStatus(UploadFileStatus.Uploaded);
                        if (curUploadWay == UploadWay.OneKey) {
                            sendUploadBroadCast(
                                    getCountByStatus(UploadFileStatus.Uploaded),
                                    waitUploadFiles != null ? waitUploadFiles
                                            .size() : 0);
                        }
                        DataManager.deleteUploadFile(uploadFile.getFileName());
                    }
                }

            }
            // 无文件可以上传
            if (files.size() < 1) {
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, "无文件可以上传");
                    UIUtil.showRemoteToast("无数据可上传");
                }
                endUpload();
                return;
            }
            ZipUtil.zipFiles(files, zipPath);
            File zipFile = new File(zipPath);
            final String zipFileName;
            if (zipFile.exists()) {
//				if (ConstantConfig.Debug) {
//					LogUtil.d(TAG, "正在上传" + zipFile.getAbsolutePath());
//					UIUtil.showRemoteToast("正在上传" + zipFile.getAbsolutePath());
//				}
                zipFileName = zipFile.getAbsolutePath();
                // fileinfo = JSON.toJSONString(upLoadDatas);
                // JSONArray jarray = null;
                // try {
                // jarray = new JSONArray(fileinfo);
                // } catch (JSONException e1) {
                // e1.printStackTrace();
                // }
                JSONObject param = new JSONObject();
                try {
                    param.put("zipFile", zipFile.getAbsolutePath());
                    param.put("starttime", starttime);
                    param.put("endtime", endtime);
                    // param.put("hrs", hrs.toString());
                    // param.put("fileinfo", jarray);
                    param.put("fileinfo", upLoadDatas);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                ClientGameService.getInstance().uploadBleFile(param,

                        new HttpReqCallBack<UploadFileResponse>() {
                            @Override
                            public Activity getReqActivity() {
                                return null;
                            }

                            @Override
                            public void doSuccess(UploadFileResponse result) {
                                if (result.isOk()) {
                                    if (ConstantConfig.Debug) {
                                        LogUtil.d(TAG, "批量上传成功");
                                        UIUtil.showRemoteToast("批量上传成功");
                                    }
                                    if (curUploadWay == UploadWay.OneKey) {
                                        sendUploadBroadCast(
                                                getCountByStatus(UploadFileStatus.Uploaded),
                                                waitUploadFiles != null ? waitUploadFiles
                                                        .size() : 0);
                                    }
                                    setUploadByStatus(UploadFileStatus.Uploaded);
                                    int undeal = getCountByStatus(UploadFileStatus.UnDeal);
                                    int failed = getCountByStatus(UploadFileStatus.UploadFailed);
                                    if (undeal + failed > 0
                                            && uploadedFileRetryTimes < UPLOAD_RETRYTIMES) {
//								if (ConstantConfig.Debug) {
//									LogUtil.d(TAG, msg + "，上传列表中还有 "
//											+ (undeal + failed) + "需要上传，继续上传");
//									UIUtil.showRemoteToast(msg + "，上传列表中还有 "
//											+ (undeal + failed)
//											+ "需要上传，尝试重新上传，尝试次数"
//											+ uploadedFileRetryTimes);
//								}
                                        uploadedFileRetryTimes++;
                                        uploadECGFile();
                                    } else {
                                        endUpload();
                                    }
                                } else {
                                    if (ConstantConfig.Debug) {
                                        LogUtil.d(TAG, "批量上传失败" + result.getMessage());
                                        UIUtil.showRemoteToast("批量上传失败"
                                                + result.getMessage());
                                    }
                                    // 是否需要重新登录
                                    // if (result.Code.endsWith("401")) {
                                    // reLogin();
                                    // }
                                    endUpload();
                                }
                            }

                            @Override
                            public void doError(String result) {
                                if (ConstantConfig.Debug) {
                                    LogUtil.d(TAG, msg + "，上传失败 result: " + result);
                                    UIUtil.showRemoteToast(msg + "，上传失败result:"
                                            + result);
                                }
                                setUploadByStatus(UploadFileStatus.UploadFailed);
                                int undeal = getCountByStatus(UploadFileStatus.UnDeal);
                                int failed = getCountByStatus(UploadFileStatus.UploadFailed);
                                if (undeal + failed > 0
                                        && uploadedFileRetryTimes < UPLOAD_RETRYTIMES) {
//							if (ConstantConfig.Debug) {
//								LogUtil.d(TAG, msg + "，上传列表中还有 " + undeal
//										+ failed + "需要上传，尝试重新上传，尝试次数"
//										+ uploadedFileRetryTimes);
//								UIUtil.showRemoteToast(msg + "，上传列表中还有 "
//										+ undeal + failed + "需要上传，尝试重新上传，尝试次数"
//										+ uploadedFileRetryTimes);
//							}
                                    // 最多尝试三次如果还是失败放弃上传，等待下次批量上传
                                    uploadedFileRetryTimes++;
                                    DataManager.updateUploadFileTimes(waitUploadFiles);
                                    uploadECGFile();
                                } else {
                                    endUpload();
                                }
                            }
                        });
            } else {
//				if (ConstantConfig.Debug) {
//					LogUtil.d(TAG, "正在上传的文件不存在" + zipFile.getAbsolutePath());
//					UIUtil.showRemoteToast("正在上传的文件不存在"
//							+ zipFile.getAbsolutePath());
//				}
                setUploadByStatus(UploadFileStatus.UploadFailed);
                // 最多尝试三次如果还是失败放弃上传，等待下次批量上传
                uploadedFileRetryTimes++;
                if (uploadedFileRetryTimes < UPLOAD_RETRYTIMES) {
                    if (ConstantConfig.Debug) {
                        LogUtil.d(TAG, msg + "，上传失败，尝试重新上传，尝试次数"
                                + uploadedFileRetryTimes);
                    }
                    DataManager.updateUploadFileTimes(waitUploadFiles);
                    uploadECGFile();
                }
            }
        } catch (Exception e) {
            if (ConstantConfig.Debug) {
                LogUtil.e(TAG + " startUpload", e);
                UIUtil.showRemoteToast("上传失败：\n" + e.toString());
            }
            setUploadByStatus(UploadFileStatus.UploadFailed);
            endUpload();
        }
    }

    UIUserInfoLogin user;

    private void endBatchUpload(List<UploadFile> batchUploadFiles) {
        try {
            List<UploadFile> failedFile = new ArrayList<UploadFile>();
            if (batchUploadFiles != null && batchUploadFiles.size() > 0) {
                for (UploadFile file : batchUploadFiles) {
                    if (file.getStatus() == UploadFileStatus.Uploaded) {
                        boolean retDelete = new File(file.getPath()).delete();
                        if (!retDelete) {
                            if (ConstantConfig.Debug) {
                                LogUtil.e(
                                        TAG,
                                        new Exception("删除文件失败："
                                                + file.getPath()));
                                UIUtil.showRemoteToast("删除文件失败："
                                        + file.getPath());
                            }
                        }
                        retDelete = new File(file.getBpath()).delete();
                        if (!retDelete) {
                            if (ConstantConfig.Debug) {
                                LogUtil.e(
                                        TAG,
                                        new Exception("删除文件失败："
                                                + file.getBpath()));
                                UIUtil.showRemoteToast("删除文件失败："
                                        + file.getBpath());
                            }
                        }
                        boolean retDeleteDB = DataManager.deleteUploadFile(file
                                .getFileName());
                        if (!retDeleteDB) {
                            if (ConstantConfig.Debug) {
                                LogUtil.e(TAG, new Exception("删除数据库记录失败："
                                        + file.getFileName()));
                                UIUtil.showRemoteToast("删除数据库记录失败："
                                        + file.getFileName());
                            }
                        }
                    } else if (file.getStatus() == UploadFileStatus.UploadFailed) {
                        failedFile.add(file);
                    }
                }
            }
            // 回写数据库
            if (failedFile.size() > 0) {
                boolean retFailedStatus = DataManager.updateUploadFileStatus(
                        failedFile, UploadFileStatus.UploadFailed);
                if (!retFailedStatus) {
                    if (ConstantConfig.Debug) {
                        LogUtil.e(TAG, "更新批量上传数据库状态失败" + failedFile.size());
                        UIUtil.showRemoteToast("更新批量上传数据库状态失败"
                                + failedFile.size());
                    }
                }
            }
        } catch (Exception e) {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, e.toString());
                UIUtil.showRemoteToast("批量上传失败" + e.toString());
            }
        } finally {
//            if (ConstantConfig.Debug) {
//                LogUtil.d(TAG, "批量上传结束");
//                UIUtil.showRemoteToast("批量上传结束");
//            }
        }
    }

    /**
     * 结束上传
     */
    private void endUpload() {
        try {
            if (curUploadWay == UploadWay.OneKey) {
                int totalUploaded = waitUploadFiles != null ? waitUploadFiles
                        .size() : 0;
                // 结束上传
                sendUploadBroadCast(totalUploaded, totalUploaded);
            }
            List<UploadFile> failedFile = new ArrayList<UploadFile>();
            if (waitUploadFiles != null && waitUploadFiles.size() > 0) {
                for (UploadFile file : waitUploadFiles) {
                    if (file.getStatus() == UploadFileStatus.Uploaded) {
                        boolean retDelete = new File(file.getPath()).delete();
                        if (!retDelete) {
                            if (ConstantConfig.Debug) {
                                LogUtil.e(
                                        TAG,
                                        new Exception("删除文件失败："
                                                + file.getPath()));
                                UIUtil.showRemoteToast("删除文件失败："
                                        + file.getPath());
                            }
                        }
                        retDelete = new File(file.getBpath()).delete();
                        if (!retDelete) {
                            if (ConstantConfig.Debug) {
                                LogUtil.e(
                                        TAG,
                                        new Exception("删除文件失败："
                                                + file.getBpath()));
                                UIUtil.showRemoteToast("删除文件失败："
                                        + file.getBpath());
                            }
                        }
                        boolean retDeleteDB = DataManager.deleteUploadFile(file
                                .getFileName());
                        if (!retDeleteDB) {
                            if (ConstantConfig.Debug) {
                                LogUtil.e(TAG, new Exception("删除数据库记录失败："
                                        + file.getFileName()));
                                UIUtil.showRemoteToast("删除数据库记录失败："
                                        + file.getFileName());
                            }
                        }
                    } else if (file.getStatus() == UploadFileStatus.UploadFailed) {
                        failedFile.add(file);
                    }
                }
            }
            // 回写数据库
            if (failedFile.size() > 0) {
                boolean retFailedStatus = DataManager.updateUploadFileStatus(
                        failedFile, UploadFileStatus.UploadFailed);
                if (!retFailedStatus) {
                    if (ConstantConfig.Debug) {
                        LogUtil.e(TAG, "更新批量上传数据库状态失败" + failedFile.size());
                        UIUtil.showRemoteToast("更新批量上传数据库状态失败"
                                + failedFile.size());
                    }
                }
            }
        } catch (Exception e) {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, e.toString());
                UIUtil.showRemoteToast("批量上传失败" + e.toString());
            }
        } finally {
//            if (ConstantConfig.Debug) {
//                LogUtil.d(TAG, "批量上传结束");
//                UIUtil.showRemoteToast("批量上传结束");
//            }
        }
    }

    private void resetUpload() {
        if (ConstantConfig.Debug) {
            LogUtil.d(TAG, curUploadWay == UploadWay.OneKey ? "结束一键上传"
                    : "结束批量上传");
            UIUtil.showRemoteToast(curUploadWay == UploadWay.OneKey ? "结束一键上传"
                    : "结束批量上传");
        }
        isUploading.set(false);
        nUploadWay = UploadWay.Batch;
    }

    /**
     * 批量或者一键上传文件
     */
    private void startUpload() {
        /**
         * 是否开启所有网络上传
         */
        // boolean netType = SettingsManager.getInstance().getSettingsNetType();
        // // 如果仅限定wifi，检查当前是否Wifi网络，如果不是取消本次上传
        // if (!netType && !NetUtil.isWifi()) {
        // LogUtil.d(TAG, "当前网络为移动网络，停止上传");
        // return;
        // }
        // UIUserInfoLogin user = DataManager.getUserInfo();
        if (!DataManager.isLogin()) {
            LogUtil.d(TAG, "用户数据不存在");
            return;
        }
        Calendar cal = null;
        // 如果是一键上传，立即做出响应，防止在设置页面，点击一键上传反应慢
        if (nUploadWay == UploadWay.OneKey) {
            cal = Calendar.getInstance();
            cal.set(Calendar.YEAR, 1970);
            List<UploadFile> waitUploadFiles = DataManager.getUploadFile(
                    cal.getTime(), -1);
            sendUploadBroadCast(0,
                    waitUploadFiles != null ? waitUploadFiles.size() : 0);
        }
        if (isUploading.compareAndSet(false, true)) {
            msg = "";
            curUploadWay = nUploadWay;
            if (curUploadWay == UploadWay.OneKey) {
                msg += "一键上传";
                cal = Calendar.getInstance();
                cal.set(Calendar.YEAR, 1970);
                waitUploadFiles = getUploadFile(cal.getTime(),
                        -ConstantConfig.Batch_Interval);
                uploadedFileRetryTimes = 0;
                if (waitUploadFiles != null) {
                    sendUploadBroadCast(
                            getCountByStatus(UploadFileStatus.Uploaded),
                            waitUploadFiles.size());
                } else {
                    sendUploadBroadCast(0, 0);
                    resetUpload();
                    return;
                }
            } else {
                msg += "批量上传";
                cal = Calendar.getInstance();
                cal.add(Calendar.MINUTE, -1);
                waitUploadFiles = getUploadFile(cal.getTime(), -1);
            }
            if (waitUploadFiles != null && waitUploadFiles.size() > 0) {
//                if (ConstantConfig.Debug) {
//                    LogUtil.d(TAG, "准备" + msg);
//                    UIUtil.showRemoteToast("准备" + msg);
//                }
                uploadECGFile();
            } else {
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, "无文件可上传");
                    UIUtil.showRemoteToast("无文件可上传" + msg);
                }
                resetUpload();
            }
        } else {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, "文件正在上传中");
                UIUtil.showRemoteToast("文件正在上传中");
            }
            return;
        }
    }

    @SuppressLint("SimpleDateFormat")
    public static void startRealTimeMode(
            final List<FileFrameData> fileFrameDatas,
            final List<Short> fileBreathDatas, final JSONArray jHeartRateArray) {
        new AsyncTask<Void, Integer, String>() {
            @Override
            protected String doInBackground(Void... params) {
                try {
//                    if (ConstantConfig.Debug) {
//                        LogUtil.d(TAG, "准备实时上传");
//                        UIUtil.showRemoteToast("准备实时上传");
//                    }
                    StringBuffer data1 = new StringBuffer();
                    StringBuffer data2 = new StringBuffer();

                    Short bdata = null;
                    FileFrameData fileFrameData = null;
                    String starttime = "";
                    Long sDate = null;
                    Long eDate = null;
                    String endtime = "";
                    long datasLength = 0;
                    for (int i = 0; i < fileFrameDatas.size(); i++) {
                        fileFrameData = fileFrameDatas.get(i);
                        data1.append(fileFrameData.ch1 + ",");
                        if ((sDate != null && fileFrameData.date < sDate)
                                || sDate == null) {
                            sDate = fileFrameData.date;
                        }
                        if ((eDate != null && fileFrameData.date > eDate)
                                || eDate == null) {
                            eDate = fileFrameData.date;
                        }
                        datasLength++;
                    }
                    if (datasLength <= 0) {
//                        if (ConstantConfig.Debug) {
//                            LogUtil.d(TAG, "实时上传  心率为空");
//                        }
                        return "实时上传无数据";
                    }
                    starttime = CommonUtil.getTime_B(new Date(sDate));
                    endtime = CommonUtil.getTime_B(new Date(eDate));
                    if (ConstantConfig.Debug) {
                        try {
                            long dataTime = CommonUtil.parseDate_B(endtime)
                                    .getTime()
                                    - CommonUtil.parseDate_B(starttime)
                                    .getTime();
                            if (datasLength == (int) (dataTime / 8) + 1) {
                                LogUtil.d(TAG, "实时上传 " + starttime + "-"
                                        + endtime + " 时差" + dataTime + " 数据长度"
                                        + datasLength);
                                // UIUtil.UIUtil.showBleToast("实时上传 " +
                                // starttime + "-"
                                // + endtime + " 时差" + dataTime + " 数据长度"
                                // + datasLength);
                            } else {
                                LogUtil.d(TAG, "实时上传 不符合规则  时差" + dataTime
                                        + " 数据长度" + datasLength);
                            }
                        } catch (Exception e) {
                        }
                    }
                    datasLength = 0;
                    for (int i = 0; i < fileBreathDatas.size(); i++) {
                        bdata = fileBreathDatas.get(i);
                        data2.append(bdata + ",");
                        datasLength++;

                    }
                    if (datasLength <= 0) {
//                        if (ConstantConfig.Debug) {
//                            LogUtil.d(TAG, "实时上传  呼吸为空");
//                        }
                        return "实时上传  呼吸为空";
                    }
                    JSONObject param = new JSONObject();
                    // param.put("ecgFile", fileUtil.getECGFile());
                    // param.put("breathFile", fileUtil.getBreathFile());
                    param.put("starttime", starttime);
                    param.put("endtime", endtime);
                    param.put("hrs", jHeartRateArray);
                    param.put("data1", data1.toString());
                    param.put("data2", data2.toString());
                    param.put("fileinfo", "");
                    ClientGameService.getInstance().uploadRealBleFile(param,
                            new HttpReqCallBack<UploadFileResponse>() {

                                @Override
                                public Activity getReqActivity() {
                                    return null;
                                }

                                @Override
                                public void doSuccess(UploadFileResponse result) {
                                    if (result.isOk()) {
                                        if (ConstantConfig.Debug) {
                                            LogUtil.d(TAG, "实时上传成功");
                                            UIUtil.showRemoteToast("实时上传成功");
                                        }
                                    } else {
                                        if (ConstantConfig.Debug) {
                                            LogUtil.d(
                                                    TAG,
                                                    "实时上传失败"
                                                            + result.getMessage());
                                            UIUtil.showRemoteToast("实时上传失败"
                                                    + result.getMessage());
                                        }
                                    }
                                }

                                @Override
                                public void doError(String result) {
                                    if (ConstantConfig.Debug) {
                                        LogUtil.d(TAG, "实时上传失败" + result);
                                        UIUtil.showRemoteToast("实时上传失败"
                                                + result);
                                    }
                                    // retryStartRealTimeUp();
                                }
                            });
                    return ResponseCode.Ok;
                } catch (Exception e) {
                    return e.toString();
                }
            }

            @Override
            protected void onPostExecute(String result) {
                if (ConstantConfig.Debug && !ResponseCode.Ok.equals(result)) {
                    LogUtil.d(TAG, "实时上传失败" + result);
                    UIUtil.showRemoteToast("实时上传失败" + result);
                }
                super.onPostExecute(result);

            }
        }.execute();
    }

    // private void retryStartRealTimeUp() {
    // // 上传失败重新3s后重新上传
    // // executor.schedule(new Runnable() {
    // // @Override
    // // public void run() {
    // // startRealTimeMode();
    // // }
    // // }, 1, TimeUnit.SECONDS);
    // }

    public class LocalBinder extends Binder {
        public BleDomainService getService() {
            return BleDomainService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();
    /**
     * 用于Handler里的消息类型
     */
    public static final int MSG_GET_HEART = 1;
    public static final int MSG_SET_HEART = 2;
    public static final int MSG_SEND_MSG = 3;
    public static final int MSG_SEND_LONGMSG = 4;
    public static final int MSG_DEBUG_CHANGED = 5;
    /**
     * 获取芯片电量
     */
    public static final int MSG_GET_POWER = 7;

    /**
     * 在Service处理Activity传过来消息的Handler
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GET_HEART:
                    // UIUtil.showBleToast("" + FilterUtil.Instance.getHeartRate());
                    if (msg.replyTo != null) {
                        Message reply = Message.obtain();
                        reply.what = MSG_SET_HEART;
                        // reply.replyTo = mMessenger;
                        // reply.obj = FilterUtil.Instance.getHeartRate();
                        // reply.obj = 0;
                        Bundle bundle = new Bundle();
                        bundle.putInt("heart", FilterUtil.Instance.getHeartRate());
                        reply.setData(bundle);
                        try {
                            msg.replyTo.send(reply);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                case MSG_GET_POWER:
                    if (msg.replyTo != null) {
                        Message reply = Message.obtain();
                        reply.what = MSG_GET_POWER;
                        Bundle bundle = new Bundle();
                        Float power = FrameDataMachine.getInstance().getPower();
                        if (power != null) {
                            bundle.putFloat("power", power);
                        }
                        reply.setData(bundle);
                        try {
                            msg.replyTo.send(reply);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                case MSG_SEND_MSG:
                    if (msg.replyTo != null) {
                        mRemoteMessenger = msg.replyTo;
                    }
                    break;
                case MSG_DEBUG_CHANGED:
                    ConstantConfig.Debug = SettingsManager.getInstance()
                            .getFactory();
                    UIUtil.showRemoteToast("开启工程模式(R)" + ConstantConfig.Debug);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    public void showToast(String content) {
        if (mRemoteMessenger != null) {
            Message reply = Message.obtain();
            reply.what = MSG_SEND_MSG;
            Bundle bundle = new Bundle();
            bundle.putString("msg", content);
            reply.setData(bundle);
            try {
                mRemoteMessenger.send(reply);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void showLongToast(String content) {
        if (mRemoteMessenger != null) {
            Message reply = Message.obtain();
            reply.what = MSG_SEND_LONGMSG;
            Bundle bundle = new Bundle();
            bundle.putString("msg", content);
            reply.setData(bundle);
            try {
                mRemoteMessenger.send(reply);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 这个Messenger可以关联到Service里的Handler，Activity用这个对象发送Message给Service，
     * Service通过Handler进行处理。
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler());
    private Messenger mRemoteMessenger = null;

    @Override
    public IBinder onBind(Intent intent) {
        ConstantConfig.Debug = SettingsManager.getInstance().getFactory();
        start();
        Instance = this;
        // return mBinder;
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        flags = START_STICKY;

        // Notification notification = new Notification();
        // notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        // startForeground(3, notification);
        return super.onStartCommand(intent, flags, startId);
    }

    private void startBatchUpload() {
//        if (ConstantConfig.Debug) {
//            LogUtil.d(TAG, "开始批量上传");
//            UIUtil.showRemoteToast("开始批量上传");
//        }
        // UIUserInfoLogin user = DataManager.getUserInfo();
        if (!DataManager.isLogin()) {
            LogUtil.d(TAG, "用户数据不存在");
            return;
        }
        Calendar cal = null;
        cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, -5);
        final List<UploadFile> batchUploadFiles = getUploadFile(cal.getTime(),
                -1);
        if (batchUploadFiles != null && batchUploadFiles.size() > 0) {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, "批量上传文件数量" + batchUploadFiles.size());
                UIUtil.showRemoteToast("批量上传文件数量" + batchUploadFiles.size());
            }
            try {
                List<File> files = null;
                JSONArray upLoadDatas = new JSONArray();
                String zipPath = FileUtil.ECG_BATCH_UPLOADDIR + "uploadData"
                        + ".zip";
                files = new ArrayList<File>();
                int count = 0;
                String starttime = "";
                String endtime = "";
                for (int i = 0; i < batchUploadFiles.size(); i++) {
                    UploadFile uploadFile = batchUploadFiles.get(i);
                    if (uploadFile.getStatus() == UploadFileStatus.Uploaded
                            || uploadFile.getStatus() == UploadFileStatus.Uploading) {
                        continue;
                    }
                    File[] wFiles = new File[]{
                            new File(uploadFile.getPath()),
                            new File(uploadFile.getBpath())};
                    for (int j = 0; j < wFiles.length; j++) {
                        File file = wFiles[j];
                        if (file.exists()) {
                            count++;
                            uploadFile.setStatus(UploadFileStatus.Uploading);
                            files.add(file);
                            JSONObject data = new JSONObject();
                            String sTime = CommonUtil.getTime_B(uploadFile
                                    .getDataBeginTime());
                            String eTime = CommonUtil.getTime_B(uploadFile
                                    .getDataEndTime());
                            data.put("starttime", sTime);
                            data.put("endtime", eTime);
                            data.put("filename", file.getName());
                            data.put("datatype", j == 0 ? "1" : "2");
                            // 抓取文件对应ble的期间段
                            if (sTime.compareTo(starttime) < 0
                                    || starttime.isEmpty()) {
                                starttime = sTime;
                            }
                            if (eTime.compareTo(endtime) > 0
                                    || endtime.isEmpty()) {
                                endtime = eTime;
                            }
                            if (j == 0) {
                                data.put("hrs",
                                        new JSONArray(uploadFile.getHrs()));
                            }
                            upLoadDatas.put(data);
                        } else {
                            if (ConstantConfig.Debug) {
                                LogUtil.d(TAG, "数据库中存在此文件，但是文件目录中找不到："
                                        + uploadFile.getFileName());
                            }
                            uploadFile.setStatus(UploadFileStatus.Uploaded);
                            DataManager.deleteUploadFile(uploadFile
                                    .getFileName());
                        }
                    }

                }
                // 无文件可以上传
                if (files.size() < 1) {
                    if (ConstantConfig.Debug) {
                        LogUtil.d(TAG, "无文件可以上传");
                        UIUtil.showRemoteToast("无数据可上传");
                    }
                    endBatchUpload(batchUploadFiles);
                    return;
                }
                ZipUtil.zipFiles(files, zipPath);
                File zipFile = new File(zipPath);
                final String zipFileName;
                if (zipFile.exists()) {
                    zipFileName = zipFile.getAbsolutePath();
                    JSONObject param = new JSONObject();
                    param.put("zipFile", zipFile.getAbsolutePath());
                    param.put("starttime", starttime);
                    param.put("endtime", endtime);
                    // param.put("hrs", hrs.toString());
                    // param.put("fileinfo", jarray);
                    param.put("fileinfo", upLoadDatas);

                    ClientGameService.getInstance().uploadBleFile(param,

                            new HttpReqCallBack<UploadFileResponse>() {
                                @Override
                                public Activity getReqActivity() {
                                    return null;
                                }

                                @Override
                                public void doSuccess(UploadFileResponse result) {
                                    if (result.isOk()) {
                                        if (ConstantConfig.Debug) {
                                            LogUtil.d(TAG, "批量上传成功");
                                            UIUtil.showRemoteToast("批量上传成功");
                                        }
                                        setUploadByStatus(batchUploadFiles,
                                                UploadFileStatus.Uploaded);
                                    } else {
                                        if (ConstantConfig.Debug) {
                                            LogUtil.d(TAG,
                                                    "批量上传失败" + result.getMessage());
                                            UIUtil.showRemoteToast("批量上传失败"
                                                    + result.getMessage());
                                        }
                                    }
                                    endBatchUpload(batchUploadFiles);
                                }

                                @Override
                                public void doError(String result) {
                                    if (ConstantConfig.Debug) {
                                        LogUtil.d(TAG, "批量上传失败 result: " + result);
                                        UIUtil.showRemoteToast("批量上传失败result:" + result);
                                    }
                                    setUploadByStatus(batchUploadFiles,
                                            UploadFileStatus.UploadFailed);
                                    endBatchUpload(batchUploadFiles);
                                }
                            });
                } else {
                    if (ConstantConfig.Debug) {
                        LogUtil.d(TAG, "正在上传的文件不存在" + zipFile.getAbsolutePath());
                        UIUtil.showRemoteToast("正在上传的文件不存在"
                                + zipFile.getAbsolutePath());
                    }
                    setUploadByStatus(batchUploadFiles,
                            UploadFileStatus.UploadFailed);
                    endBatchUpload(batchUploadFiles);
                }
            } catch (Exception e) {
                if (ConstantConfig.Debug) {
                    LogUtil.e(TAG + "批量上传", e);
                    UIUtil.showRemoteToast("批量上传：\n" + e.toString());
                }
                setUploadByStatus(batchUploadFiles,
                        UploadFileStatus.UploadFailed);
                endBatchUpload(batchUploadFiles);
            }
        } else {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, "无批量文件可上传");
                UIUtil.showRemoteToast("无批量文件可上传");
            }
        }
    }

    /**
     * 进程间通信
     */
    public Integer getHeartRate() {
        return FilterUtil.Instance.getHeartRate();
    }

    public static FileUtil writeECGData2File(
            List<FileFrameData> fileFrameDatas, List<Short> fileBreathDatas) {
        if (fileFrameDatas.size() > 0) {
            try {
                int capacity = SettingsManager.getInstance()
                        .getSettingsOffData();
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, ("批量") + "开始写文件(可用空间" + capacity + "MB)");
                }
                if (capacity > 0) {
//                    if (ConstantConfig.Debug) {
//                        String starttime = "";
//                        Long sDate = null;
//                        Long eDate = null;
//                        String endtime = "";
//                        long datasLength = 0;
//                        for (int i = 0; i < fileFrameDatas.size(); i++) {
//                            FileFrameData fileFrameData = fileFrameDatas.get(i);
//                            if ((sDate != null && fileFrameData.date < sDate)
//                                    || sDate == null) {
//                                sDate = fileFrameData.date;
//                            }
//                            if ((eDate != null && fileFrameData.date > eDate)
//                                    || eDate == null) {
//                                eDate = fileFrameData.date;
//                            }
//                            datasLength++;
//                        }
//                        starttime = CommonUtil.getTime_B(new Date(sDate));
//                        endtime = CommonUtil.getTime_B(new Date(eDate));
//                        try {
//                            long dataTime = CommonUtil.parseDate_B(endtime)
//                                    .getTime()
//                                    - CommonUtil.parseDate_B(starttime)
//                                    .getTime();
//                            if (datasLength == (int) (dataTime / 8) + 1) {
//                                LogUtil.d(TAG, "批量写文件" + starttime + "-"
//                                        + endtime + " 时差" + dataTime + " 数据长度"
//                                        + datasLength);
//                                UIUtil.showRemoteLongToast("批量写文件" + starttime
//                                        + "-" + endtime + " 时差" + dataTime
//                                        + " 数据长度" + datasLength);
//                            } else {
//                                LogUtil.e(TAG, "批量写文件不符合规则  时差" + dataTime
//                                        + " 数据长度" + datasLength);
//                                UIUtil.showRemoteToast("批量写文件不符合规则  时差"
//                                        + dataTime + " 数据长度" + datasLength);
//                            }
//                        } catch (Exception e) {
//                            e.printStackTrace();
//                        }
//
//                    }
                    FileUtil fileUtil = new FileUtil();
                    fileUtil.beginWriteFile();
                    fileUtil.writeBlock(fileFrameDatas);
                    fileUtil.endWriteFile();
                    fileUtil.writeBreathData(fileBreathDatas);
                    if (BleDomainService.Instance != null) {
                        BleDomainService.Instance.startBatchUpload();
                    } else {
                        if (ConstantConfig.Debug) {
                            UIUtil.showToast("BleDomainService没有实例");
                            LogUtil.e(TAG, "BleDomainService没有实例");
                        }
                    }
                    return fileUtil;
                } else {
                    if (ConstantConfig.Debug) {
                        LogUtil.d(TAG, "无存储空间可用");
                    }
                }
                if (ConstantConfig.Debug) {
                    LogUtil.d(TAG, ("批量") + "结束写文件");
                }
            } catch (Exception e) {
                LogUtil.e(TAG, e);
                e.printStackTrace();
            }
        } else {
            if (ConstantConfig.Debug) {
                LogUtil.d(TAG, "无蓝牙数据可写入文件");
            }
        }
        return null;

    }

    private void start() {
        // if (mFuture != null && !mFuture.isDone()) {
        // mFuture.cancel(true);
        // }
        // mFuture = executor.scheduleAtFixedRate(new Runnable() {
        // @Override
        // public void run() {
        // // if (ConstantConfig.Debug) {
        // // LogUtil.d(TAG, "启动上传");
        // // }
        // if (atomicBooleanExecutor.compareAndSet(false, true)) {
        // try {
        // // 批量写文件
        // // writeECGData2File(false);
        // // 不用限制是否有数据
        // // if (file == null)
        // // return;
        // if (curUploadWay == UploadWay.OneKey) {
        // if (ConstantConfig.Debug) {
        // UIUtil.showBleToast("当前一键上传中，取消本次批量上传");
        // }
        // return;
        // }
        // startUpload();
        // } catch (Exception e) {
        // if (ConstantConfig.Debug) {
        // LogUtil.e(TAG, e);
        // UIUtil.showBleToast("批量上传发生异常" + e.toString());
        // }
        // } finally {
        // atomicBooleanExecutor.set(false);
        // }
        // }
        // }
        // }, 1, ConstantConfig.Batch_Interval, TimeUnit.SECONDS);
        registerReceiver(mGattUpdateReceiver, makeUploadIntentFilter());
        registerReceiver(batteryReceiver, makeBatteryIntentFilter());
    }

    private IntentFilter makeBatteryIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        return intentFilter;
    }

    private void end() {
        unregisterReceiver(mGattUpdateReceiver);
        unregisterReceiver(batteryReceiver);
        // if (mFuture != null && !mFuture.isDone()) {
        // mFuture.cancel(true);
        // }
        // if (executor != null) {
        // executor.shutdown();
        // }
    }

    private static IntentFilter makeUploadIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BleDomainService.ACTION_UPLOAD_STARTREALMODE);
        intentFilter.addAction(BleDomainService.ACTION_UPLOAD_ENDREALMODE);
        intentFilter.addAction(BleDomainService.ACTION_UPLOAD_STARTONEKEYMODE);
        intentFilter.addAction(BleDomainService.ACTION_UPLOAD_ENDONEKEYMODE);
        return intentFilter;
    }

    @Override
    public void onLowMemory() {
        LogUtil.w(ConstantConfig.DebugTAG, TAG + "\n" + "onLowMemory");
        super.onLowMemory();
    }

    @Override
    public boolean stopService(Intent name) {
        LogUtil.w(ConstantConfig.DebugTAG, TAG + "\n" + "stopService");
        showLongToast("LowMemory");
        return super.stopService(name);
    }

    @Override
    public void onDestroy() {
        end();
        Instance = null;
        super.onDestroy();
    }
}
