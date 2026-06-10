import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.List;
import javax.sound.sampled.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Font;
import java.util.Base64;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

import ph.lookph;
import yemian.twoyemian; // 导入新的登录界面
import yemian.namelog;  // 导入消息发送限制类
import yemian.banbenhao; // 导入版本号类
import ph.puh; // 导入图片传输类


public class ChatClient extends JFrame implements twoyemian.LoginCallback, ph.puh.ImageMessageCallback {
    private JTextArea logArea;       // 聊天记录显示区域
    private JTextPane logPane;       // 新增：用于显示带按钮的消息
    private JTextField nicknameField; // 昵称输入框
    private JTextField inputField;   // 消息输入框
    private JButton connectBtn;      // 连接按钮
    private JButton sendBtn;         // 发送按钮
    private JButton voiceBtn;        // 语音按钮
    private JButton changeNicknameBtn; // 更改昵称按钮
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isConnected = false;
    private static final String SERVER_IP = "xxx.xxx.xxx.xxx";   // 锁定的服务器IP
    private static final int SERVER_PORT = 255;               // 锁定的服务器端口

    // 语音相关
    private TargetDataLine targetDataLine;
    private ByteArrayOutputStream byteArrayOutputStream;
    private boolean isRecording = false;
    private Thread recordingThread;
    private javax.swing.Timer recordingTimer;
    private int recordingSecondsLeft = 0;
    private JLabel recordingTimerLabel;

    // 语音消息存储
    private Map<String, byte[]> voiceMessages = new HashMap<>();
    private int voiceMessageCounter = 0;

    // 在类的字段部分添加存储图片数据的映射
    private String latestImageId = null;
    private Map<String, String> receivedImages = new HashMap<>(); // 存储接收到的图片数据
    private String latestImageName = null; // 存储最新图片名称
    
    // 添加查看最新图片的按钮
    private JButton viewLatestImageButton = null;
    
    // 添加消息显示面板
    private JPanel messagePanel;
    private JScrollPane messageScrollPane;
    
    // 在类的字段部分添加当前用户和群组信息
    private String currentUsername;
    private String currentUserGroup;
    private String currentUserPassword; // 添加密码字段

    // 点对点聊天相关字段
    private String myP2PPassword;
    private List<String> onlineUsers = new ArrayList<>();
    private JPanel onlineUsersPanel; // 显示在线用户的面板
    private Map<String, P2PChatWindow> p2pWindows = new HashMap<>(); // 点对点聊天窗口映射
    private JLabel p2pPasswordLabel; // 显示自己的点对点密码标签
    private String pendingP2PTargetUser; // 待处理的点对点目标用户
    private String pendingP2PPassword; // 待处理的点对点密码
    
    // 单例锁文件相关字段
    private static FileLock applicationLock = null;
    private static FileChannel lockChannel = null;
    private static final String LOCK_FILE_NAME = ".chatspeak_instance.lock";

    // 昵称文件路径
    private String nicknameFilePath;

    // 在类的字段部分添加聊天记录文件路径
    private String chatHistoryFilePath;

    // 在类的字段部分添加暂存消息队列
    private List<String> pendingMessages = new ArrayList<>();

    // 添加图片发送限制相关字段
    public static final long IMAGE_SEND_INTERVAL = 60 * 1000; // 1分钟间隔
    public static final long MAX_IMAGE_SIZE = 20 * 1024 * 1024; // 20MB
    private long lastImageSendTime = 0; // 上次发送图片的时间
    
    // 记录最后发送真实消息（非/ping）的时间
    private long lastRealMessageSentTime = 0;

    // 添加getter和setter方法
    public long getLastImageSendTime() {
        return lastImageSendTime;
    }
    
    public void setLastImageSendTime(long lastImageSendTime) {
        this.lastImageSendTime = lastImageSendTime;
    }

    // 添加应用层加密相关字段
    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private SecretKey secretKey;
    
    // 在构造函数中生成密钥
    public ChatClient() {
        // 检查版本号兼容性
        if (!banbenhao.isVersionCompatible()) {
            JOptionPane.showMessageDialog(null, 
                "客户端版本过低，请更新到最新版本！\n当前版本: " + banbenhao.getClientVersion() + 
                "\n最低要求版本: " + banbenhao.getMinRequiredVersion(), 
                "版本不兼容", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        
        // 生成加密密钥
        generateSecretKey();
        
        // 使用新的登录界面
        new twoyemian(this);
    }
    
    // 生成加密密钥
    private void generateSecretKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM);
            keyGenerator.init(128);
            secretKey = keyGenerator.generateKey();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // 加密消息
    private String encryptMessage(String message) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedBytes = cipher.doFinal(message.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return message; // 加密失败时返回原始消息
        }
    }
    
    // 解密消息
    private String decryptMessage(String encryptedMessage) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedMessage));
            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            return encryptedMessage; // 解密失败时返回加密的消息
        }
    }
    
    // 实现登录回调接口
    @Override
    public void onLoginSuccess(String username, String group, String password) {
        currentUsername = username;
        currentUserGroup = group;
        currentUserPassword = password; // 保存密码用于服务器验证
        showMainUI();
    }

    // 显示主界面
    private void showMainUI() {
        initUI();
        loadNickname(); // 加载上次保存的昵称
        checkAudioSupport(); // 检查音频支持
        startCleanupTask(); // 启动清理任务
        testImageDisplay(); // 添加测试图片显示功能
        setTitle("聊天客户端 - " + currentUsername + " (版本: " + banbenhao.getClientVersion() + ")");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        // 添加窗口关闭监听器，确保断开服务器连接
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
                // 关闭所有点对点聊天窗口（复制值以避免并发修改）
                List<P2PChatWindow> windows = new ArrayList<>(p2pWindows.values());
                for (P2PChatWindow window : windows) {
                    window.dispose();
                }
                p2pWindows.clear();
            }
        });
        setVisible(true);
    }

    // 启动清理任务
    private void startCleanupTask() {
        cleanupTimer = new javax.swing.Timer(10000, e -> puh.cleanupExpiredReceivers()); // 每10秒清理一次
        cleanupTimer.start();
    }

    // 重写窗口关闭事件，确保清理定时任务和释放锁
    @Override
    protected void processWindowEvent(WindowEvent e) {
        if (e.getID() == WindowEvent.WINDOW_CLOSING) {
            if (cleanupTimer != null) {
                cleanupTimer.stop();
            }
            // 释放程序锁
            releaseLock();
        }
        super.processWindowEvent(e);
    }

    private void initUI() {
        setLayout(new BorderLayout());

        // 顶部连接栏（只显示昵称输入框）
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        nicknameField = new JTextField("", 15);  // 昵称输入框
        nicknameField.setEditable(false); // 锁定昵称输入框
        
        connectBtn = new JButton("连接");
        changeNicknameBtn = new JButton("更改名称");
        changeNicknameBtn.addActionListener(new ChangeNicknameListener());
        
        gbc.gridx = 0; gbc.gridy = 0;
        topPanel.add(new JLabel("昵称:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        topPanel.add(nicknameField, gbc);
        
        gbc.gridx = 2; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        topPanel.add(changeNicknameBtn, gbc);
        
        gbc.gridx = 3; gbc.gridy = 0;
        topPanel.add(connectBtn, gbc);
        
        // DeepSeek AI问答按钮
        JButton deepseekBtn = new JButton("Beta DeepSeek LITE");
        deepseekBtn.addActionListener(e -> {
            // 弹出输入对话框获取问题
            String question = JOptionPane.showInputDialog(ChatClient.this, 
                "请输入您的问题（最多300字符）：", "DeepSeek AI问答", JOptionPane.QUESTION_MESSAGE);
            if (question != null && !question.trim().isEmpty()) {
                question = question.trim();
                if (question.length() > 300) {
                    JOptionPane.showMessageDialog(ChatClient.this, 
                        "问题长度不能超过300字符", "输入错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                // 发送DeepSeek请求到服务器
                if (out != null) {
                    out.println("/deepseek|" + question);
                    lastRealMessageSentTime = System.currentTimeMillis();
                    log("[系统] 已发送DeepSeek问题，等待回答...");
                } else {
                    JOptionPane.showMessageDialog(ChatClient.this, 
                        "未连接到服务器", "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        gbc.gridx = 4; gbc.gridy = 0;
        topPanel.add(deepseekBtn, gbc);
        
        add(topPanel, BorderLayout.NORTH);

        // 中间消息显示区域 - 使用JPanel和BoxLayout来支持按钮
        messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messageScrollPane = new JScrollPane(messagePanel);
        messageScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(messageScrollPane, BorderLayout.CENTER);

        // 在线用户面板
        onlineUsersPanel = new JPanel();
        onlineUsersPanel.setLayout(new BoxLayout(onlineUsersPanel, BoxLayout.Y_AXIS));
        onlineUsersPanel.setBorder(BorderFactory.createTitledBorder("在线用户"));
        // 创建密码标签面板
        JPanel passwordPanel = new JPanel(new BorderLayout());
        p2pPasswordLabel = new JLabel("我的点对点密码: 等待分配");
        p2pPasswordLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        p2pPasswordLabel.setHorizontalAlignment(SwingConstants.CENTER);
        passwordPanel.add(p2pPasswordLabel, BorderLayout.NORTH);
        // 将在线用户面板放入滚动面板
        JScrollPane onlineUsersScroll = new JScrollPane(onlineUsersPanel);
        onlineUsersScroll.setPreferredSize(new Dimension(150, 0));
        passwordPanel.add(onlineUsersScroll, BorderLayout.CENTER);
        add(passwordPanel, BorderLayout.EAST);

        // 底部输入栏
        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField(20);
        sendBtn = new JButton("发送");
        JButton sendImageBtn = new JButton("发送图片"); // 添加发送图片按钮
        sendImageBtn.addActionListener(e -> {
            inputField.setText("/sendimage");
            sendBtn.doClick(); // 触发发送按钮事件
        });
        
        // 添加查看最新图片按钮
        viewLatestImageButton = new JButton("查看最新图片");
        viewLatestImageButton.setEnabled(false); // 初始时禁用
        viewLatestImageButton.addActionListener(e -> {
            if (latestImageId != null && latestImageName != null) {
                String imageData = receivedImages.get(latestImageId);
                if (imageData != null) {
                    lookph.displayImage(imageData, latestImageName);
                } else {
                    JOptionPane.showMessageDialog(ChatClient.this, "图片数据不存在", "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        
        voiceBtn = new JButton("按住说话");
        voiceBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startRecording();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                stopRecording();
            }
        });

        // 录音计时器标签
        recordingTimerLabel = new JLabel("8秒");
        recordingTimerLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        recordingTimerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        recordingTimerLabel.setVisible(false); // 初始隐藏

        // 添加字节计数标签
        JPanel inputWrapper = new JPanel(new BorderLayout());
        byteCountLabel = new JLabel("0/" + MAX_MESSAGE_BYTES + " 字节");
        byteCountLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        byteCountLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        byteCountLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));

        // 监听输入框内容变化
        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateByteCount();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateByteCount();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateByteCount();
            }
        });

        inputWrapper.add(inputField, BorderLayout.CENTER);
        inputWrapper.add(byteCountLabel, BorderLayout.EAST);

        JPanel buttonPanel = new JPanel(new FlowLayout()); // 创建按钮面板
        buttonPanel.add(sendBtn);
        buttonPanel.add(sendImageBtn); // 添加发送图片按钮到面板
        buttonPanel.add(viewLatestImageButton); // 添加查看最新图片按钮
        
        inputPanel.add(inputWrapper, BorderLayout.CENTER);
        inputPanel.add(buttonPanel, BorderLayout.EAST); // 将按钮面板添加到输入面板

        bottomPanel.add(inputPanel, BorderLayout.CENTER);
        // 语音按钮和计时器面板
        JPanel voicePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        voicePanel.add(voiceBtn);
        voicePanel.add(recordingTimerLabel);
        bottomPanel.add(voicePanel, BorderLayout.SOUTH);
        add(bottomPanel, BorderLayout.SOUTH);

        // 按钮事件
        connectBtn.addActionListener(new ConnectListener());
        sendBtn.addActionListener(new SendListener());
        inputField.addActionListener(new SendListener());  // 回车发送
    }

    // 更改昵称监听器
    private class ChangeNicknameListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            // 创建更改昵称对话框
            JDialog dialog = new JDialog(ChatClient.this, "更改昵称", true);
            dialog.setSize(300, 150);
            dialog.setLocationRelativeTo(ChatClient.this);
            dialog.setLayout(new BorderLayout());

            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            JTextField newNicknameField = new JTextField(15);
            JPasswordField rootKeyField = new JPasswordField(15);
            JButton confirmBtn = new JButton("确认");
            JButton cancelBtn = new JButton("取消");

            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("新昵称:"), gbc);
            gbc.gridx = 1; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(newNicknameField, gbc);

            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel("root密钥:"), gbc);
            gbc.gridx = 1; gbc.gridy = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(rootKeyField, gbc);

            JPanel buttonPanel = new JPanel(new FlowLayout());
            buttonPanel.add(confirmBtn);
            buttonPanel.add(cancelBtn);
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.CENTER;
            panel.add(buttonPanel, gbc);

            confirmBtn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent evt) {
                    String newNickname = newNicknameField.getText().trim();
                    String rootKey = new String(rootKeyField.getPassword()).trim();

                    // 检查昵称长度
                    if (!namelog.isValidNickname(newNickname)) {
                        JOptionPane.showMessageDialog(dialog,
                            "昵称长度超过限制（最多" + namelog.getMaxNicknameBytes() + "字节）！",
                            "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    if (newNickname.isEmpty()) {
                        JOptionPane.showMessageDialog(dialog, "请输入新昵称！", "提示", JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    if (!"feixueloveljm".equals(rootKey)) {
                        JOptionPane.showMessageDialog(dialog, "root密钥错误！", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }

                    // 更新昵称
                    nicknameField.setText(newNickname);
                    nicknameField.setEditable(false); // 确保保持锁定状态
                    saveNickname(newNickname);
                    dialog.dispose();

                    JOptionPane.showMessageDialog(ChatClient.this, "昵称已更新！", "提示", JOptionPane.INFORMATION_MESSAGE);
                }
            });

            cancelBtn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent evt) {
                    dialog.dispose();
                }
            });

            dialog.add(panel, BorderLayout.CENTER);
            dialog.setVisible(true);
        }
    }

    // 开始录音
    private void startRecording() {
        // 检查语音功能是否可用
        if (voiceBtn != null && !voiceBtn.isEnabled()) {
            log("语音功能不可用\n");
            return;
        }

        // 确保之前的操作已完成
        if (isRecording) {
            return;
        }

        // 启动录音计时器（8秒限制）
        recordingSecondsLeft = 8;
        SwingUtilities.invokeLater(() -> {
            recordingTimerLabel.setText(recordingSecondsLeft + "秒");
            recordingTimerLabel.setVisible(true);
        });
        if (recordingTimer != null) {
            recordingTimer.stop();
        }
        recordingTimer = new javax.swing.Timer(1000, e -> {
            recordingSecondsLeft--;
            if (recordingSecondsLeft <= 0) {
                recordingTimer.stop();
                SwingUtilities.invokeLater(() -> {
                    recordingTimerLabel.setText("0秒");
                });
                // 自动停止录音
                stopRecording();
            } else {
                SwingUtilities.invokeLater(() -> {
                    recordingTimerLabel.setText(recordingSecondsLeft + "秒");
                });
            }
        });
        recordingTimer.start();

        new Thread(() -> {
            TargetDataLine localTargetDataLine = null;
            try {
                AudioFormat format = getSupportedFormat();
                if (format == null) {
                    SwingUtilities.invokeLater(() -> {
                        log("找不到支持的录音格式\n");
                        disableVoiceFeature();
                    });
                    return;
                }

                log("使用格式: " + format.toString() + "\n");

                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                localTargetDataLine = (TargetDataLine) AudioSystem.getLine(info);
                localTargetDataLine.open(format);
                localTargetDataLine.start();

                // 赋值给成员变量
                targetDataLine = localTargetDataLine;

                byteArrayOutputStream = new ByteArrayOutputStream();
                isRecording = true;

                recordingThread = new Thread(() -> {
                    byte[] buffer = new byte[4096];
                    while (isRecording && !Thread.currentThread().isInterrupted()) {
                        try {
                            int count = targetDataLine.read(buffer, 0, buffer.length);
                            if (count > 0 && isRecording) {
                                byteArrayOutputStream.write(buffer, 0, count);
                            }
                        } catch (Exception e) {
                            log("录音过程中出现错误: " + e.getMessage() + "\n");
                            break;
                        }
                    }
                });
                recordingThread.setDaemon(true);
                recordingThread.start();

                SwingUtilities.invokeLater(() -> {
                    voiceBtn.setText("松开发送");
                });
            } catch (LineUnavailableException e) {
                // 确保清理资源
                if (localTargetDataLine != null) {
                    try {
                        if (localTargetDataLine.isOpen()) {
                            localTargetDataLine.close();
                        }
                    } catch (Exception closeException) {
                        // 忽略关闭异常
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    log("无法访问录音设备: " + e.getMessage() + "\n");
                    disableVoiceFeature();
                });
            } catch (Exception e) {
                // 确保清理资源
                if (localTargetDataLine != null) {
                    try {
                        if (localTargetDataLine.isOpen()) {
                            localTargetDataLine.close();
                        }
                    } catch (Exception closeException) {
                        // 忽略关闭异常
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    log("录音初始化失败: " + e.getMessage() + "\n");
                    disableVoiceFeature();
                });
            }
        }).start();
    }

    // 停止录音
    private void stopRecording() {
        // 停止录音计时器
        if (recordingTimer != null) {
            recordingTimer.stop();
        }
        SwingUtilities.invokeLater(() -> {
            recordingTimerLabel.setVisible(false);
            recordingTimerLabel.setText("8秒"); // 重置为初始文本
        });
        
        if (!isRecording) {
            return;
        }

        isRecording = false;

        new Thread(() -> {
            try {
                // 等待录音线程结束，最多等待5秒
                if (recordingThread != null) {
                    recordingThread.join(5000); // 5秒超时
                    if (recordingThread.isAlive()) {
                        recordingThread.interrupt(); // 强制中断
                    }
                }

                // 关闭录音设备
                if (targetDataLine != null) {
                    targetDataLine.stop();
                    targetDataLine.close();
                }

                // 获取录音数据
                final byte[] audioData;
                if (byteArrayOutputStream != null && byteArrayOutputStream.size() > 0) {
                    audioData = byteArrayOutputStream.toByteArray();
                } else {
                    audioData = null;
                }
                byteArrayOutputStream = null;

                // 在EDT中更新UI
                SwingUtilities.invokeLater(() -> {
                    voiceBtn.setText("按住说话");

                    // 如果有录音数据，则发送语音消息
                    if (audioData != null && audioData.length > 0) {
                        sendVoiceMessage(audioData);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    voiceBtn.setText("按住说话"); // 确保按钮文字恢复
                    log("停止录音失败: " + e.getMessage() + "\n");
                });
            }
        }).start();
    }

    // 发送语音消息
    private void sendVoiceMessage(byte[] audioData) {
        if (!isConnected) {
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(ChatClient.this, "请先连接服务器！", "提示", JOptionPane.WARNING_MESSAGE)
            );
            return;
        }

        // 检查消息发送限制
        namelog.SendMessageResult result = namelog.canSendMessage(currentUsername, "[语音消息]");
        if (!result.isAllowed()) {
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(ChatClient.this, result.getMessage(), "发送失败", JOptionPane.WARNING_MESSAGE)
            );
            log("语音消息发送被拒绝: " + result.getMessage() + "\n");
            return;
        }

        // 在独立的后台线程中执行所有操作
        new Thread(() -> {
            try {
                // 生成语音消息ID
                String voiceId = "voice_" + System.currentTimeMillis() + "_" + (++voiceMessageCounter);
                voiceMessages.put(voiceId, audioData);

                // 将音频数据编码为Base64字符串
                String encodedAudio = Base64.getEncoder().encodeToString(audioData);
                String voiceMessage = "/voice|" + voiceId + "|" + encodedAudio;

                // 发送到服务器
                if (out != null) {
                    out.println(voiceMessage);
                    lastRealMessageSentTime = System.currentTimeMillis();
                }

                // 在EDT中更新UI
                SwingUtilities.invokeLater(() -> {
                    logVoiceMessage(currentUsername, voiceId, true);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("发送语音消息失败: " + e.getMessage() + "\n");
                });
            }
        }, "VoiceMessageSender").start();
    }

    // 播放语音消息
    private void playVoiceMessage(String voiceId) {
        log("尝试播放语音消息: " + voiceId + "\n");

        byte[] audioData = voiceMessages.get(voiceId);
        if (audioData == null) {
            log("找不到语音消息: " + voiceId + "\n");
            return;
        }

        log("找到语音消息，大小: " + audioData.length + " 字节\n");

        if (audioData.length == 0) {
            log("语音消息数据为空\n");
            return;
        }

        new Thread(() -> {
            try {
                AudioFormat format = getSupportedFormat();
                if (format == null) {
                    log("找不到支持的播放格式\n");
                    return;
                }

                log("使用音频格式: " + format.toString() + "\n");

                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
                sourceDataLine.open(format);
                sourceDataLine.start();

                log("开始播放音频...\n");

                ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
                byte[] buffer = new byte[4096];
                int bytesRead;
                int totalBytes = 0;

                while ((bytesRead = bais.read(buffer)) != -1) {
                    sourceDataLine.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }

                log("写入音频数据: " + totalBytes + " 字节\n");

                sourceDataLine.drain();
                sourceDataLine.close();
                bais.close();

                log("音频播放完成\n");
            } catch (LineUnavailableException e) {
                log("音频设备不可用: " + e.getMessage() + "\n");
            } catch (Exception e) {
                log("播放语音消息失败: " + e.getMessage() + "\n");
                e.printStackTrace(); // 打印完整的异常信息
            }
        }).start();
    }

    // 加载保存的昵称
    private void loadNickname() {
        try {
            nicknameFilePath = getNicknameFilePath();
            File nicknameFile = new File(nicknameFilePath);
            if (nicknameFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(nicknameFile))) {
                    String nickname = reader.readLine();
                    if (nickname != null && !nickname.trim().isEmpty()) {
                        // 检查昵称长度
                        if (namelog.isValidNickname(nickname)) {
                            nicknameField.setText(nickname.trim());
                            nicknameField.setEditable(false); // 如果已有昵称，则锁定
                            changeNicknameBtn.setEnabled(true); // 启用更改按钮
                        } else {
                            // 昵称不合法，清除并允许重新输入
                            nicknameField.setText("");
                            nicknameField.setEditable(true);
                            changeNicknameBtn.setEnabled(false);
                        }
                    } else {
                        nicknameField.setEditable(true); // 如果没有昵称，允许编辑
                        changeNicknameBtn.setEnabled(false); // 禁用更改按钮
                    }
                }
            } else {
                nicknameField.setEditable(true); // 如果文件不存在，允许编辑
                changeNicknameBtn.setEnabled(false); // 禁用更改按钮
            }
        } catch (IOException e) {
            // 文件不存在或读取失败，允许编辑
            nicknameField.setEditable(true);
            changeNicknameBtn.setEnabled(false);
        }
    }

    // 获取昵称文件路径（系统根目录的隐藏文件夹中）
    private String getNicknameFilePath() {
        String userHome = System.getProperty("user.home");
        String hiddenDirPath = userHome + File.separator + ".chatspeak";
        File hiddenDir = new File(hiddenDirPath);
        if (!hiddenDir.exists()) {
            hiddenDir.mkdirs();
        }
        return hiddenDirPath + File.separator + "chatspeak.txt";
    }

    // 保存昵称到文件
    private void saveNickname(String nickname) {
        try {
            nicknameFilePath = getNicknameFilePath();
            try (PrintWriter writer = new PrintWriter(new FileWriter(nicknameFilePath))) {
                writer.println(nickname);
            }
        } catch (IOException e) {
            log("保存昵称失败: " + e.getMessage() + "\n");
        }
    }

    // 连接服务器逻辑
    private class ConnectListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            if (isConnected) {
                disconnect();
                return;
            }
            
            // 检查昵称是否为空
            String nickname = nicknameField.getText().trim();
            if (nickname.isEmpty()) {
                JOptionPane.showMessageDialog(ChatClient.this, "请输入昵称！", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            // 检查昵称长度
            if (!namelog.isValidNickname(nickname)) {
                JOptionPane.showMessageDialog(ChatClient.this, 
                    "昵称长度超过限制（最多" + namelog.getMaxNicknameBytes() + "字节）！", 
                    "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 保存昵称并锁定输入框
            saveNickname(nickname);
            nicknameField.setEditable(false); // 锁定昵称输入框
            changeNicknameBtn.setEnabled(true); // 启用更改按钮
            
            new Thread(() -> {
                try {
                    // 使用普通Socket连接而不是SSL
                    socket = new Socket(SERVER_IP, SERVER_PORT);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                    isConnected = true;

                    // 连接成功后，先发送版本号信息，再发送群组信息，最后发送昵称到服务器
                    out.println("/version|" + banbenhao.getClientVersion());  // 发送版本号信息
                    out.println("/group|" + currentUserGroup);  // 发送群组信息
                    out.println("/nickname|" + nickname);  // 发送昵称到服务器

                    SwingUtilities.invokeLater(() -> {
                        log("连接成功！"); // 修改提示信息
                        connectBtn.setText("断开连接");
                        inputField.requestFocus();
                        // 启动心跳机制
                        startHeartbeat();
                        // 不再加载本地聊天记录，只接收服务器发送的历史记录
                        isChatHistoryLoaded = false; // 确保可以接收服务器历史记录
                    });

                    // 启动消息接收线程
                    new Thread(() -> {
                        try {
                            String message;
                            while ((message = in.readLine()) != null) {
                                processReceivedMessage(message);
                            }
                        } catch (IOException ex) {
                            SwingUtilities.invokeLater(() -> {
                                if (isConnected) {
                                    log("与服务器断开连接");
                                    connectBtn.setText("连接");
                                    isConnected = false;
                                }
                            });
                        }
                    }).start();

                } catch (Exception ex) {
                    showError("连接失败: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }).start();
        }
    }

    // 处理接收到的消息
    private void processReceivedMessage(String message) {
        // 处理心跳回复
        if (message.equals("/pong")) {
            // 收到服务器的心跳回复，无需特殊处理
            return;
        }
        // 处理版本验证结果
        if (message.startsWith("/version_check|")) {
            String result = message.substring(15); // 提取验证结果
            if ("failed".equals(result)) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ChatClient.this,
                        "客户端版本过低，请更新到最新版本后再试！",
                        "版本验证失败", JOptionPane.ERROR_MESSAGE);
                    disconnect();
                });
                return;
            } else if ("success".equals(result)) {
                SwingUtilities.invokeLater(() -> {
                    log("版本验证成功，正在连接...\n");
                });
            }
        }
        // 处理登录验证结果
        else if (message.startsWith("/login_result|")) {
            String result = message.substring(14); // 提取登录结果
            if ("failed".equals(result)) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ChatClient.this,
                        "群组密码错误，请重新登录！", "登录失败", JOptionPane.ERROR_MESSAGE);
                    disconnect();
                });
                return;
            } else if ("success".equals(result)) {
                SwingUtilities.invokeLater(() -> {
                    log("群组密码验证成功\n");
                });
            }
        }
        // 处理历史聊天记录
        else if (message.startsWith("/history|")) {
            String historyContent = message.substring(9); // 提取历史记录内容
            if ("start".equals(historyContent)) {
                // 开始接收历史记录时检查是否已加载
                if (isChatHistoryLoaded) {
                    return; // 如果已经加载过，忽略新的历史记录
                }
                SwingUtilities.invokeLater(() -> log("--- 历史聊天记录开始 ---\n"));
            } else if ("end".equals(historyContent)) {
                // 结束接收历史记录
                if (!isChatHistoryLoaded) {
                    isChatHistoryLoaded = true; // 标记为已加载
                    SwingUtilities.invokeLater(() -> log("--- 历史聊天记录结束 ---\n"));
                }
            } else {
                // 只有在未加载过的情况下才显示历史记录
                if (!isChatHistoryLoaded) {
                    SwingUtilities.invokeLater(() -> log(historyContent + "\n"));
                }
            }
        }
        // 处理在线用户列表
        else if (message.startsWith("/online_users|")) {
            String userList = message.substring(14); // 提取用户列表
            SwingUtilities.invokeLater(() -> updateOnlineUsers(userList));
        }
        // 处理点对点密码
        else if (message.startsWith("/p2p_password|")) {
            myP2PPassword = message.substring(14); // 提取密码
            SwingUtilities.invokeLater(() -> showMyP2PPassword());
        }
        // 处理语音消息（新格式：包含发送者信息）
        else if (message.startsWith("/voice_with_sender|")) {
            // 格式: /voice_with_sender|sender|voiceId|encodedAudio
            String[] parts = message.split("\\|", 4);
            if (parts.length == 4) {
                String sender = parts[1];
                String voiceId = parts[2];
                String encodedAudio = parts[3];

                // 解码音频数据 (在后台线程中进行)
                new Thread(() -> {
                    try {
                        byte[] audioData = Base64.getDecoder().decode(encodedAudio);
                        voiceMessages.put(voiceId, audioData); // 确保语音消息被存储

                        SwingUtilities.invokeLater(() -> {
                            // 显示带播放按钮的语音消息
                            boolean isOwnMessage = sender.equals(currentUsername);
                            logVoiceMessage(sender, voiceId, isOwnMessage);
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> log("解析语音消息失败: " + e.getMessage() + "\n"));
                    }
                }).start();
            }
        }
        // 处理语音消息（旧格式：向后兼容）
        else if (message.startsWith("/voice|")) {
            String[] parts = message.split("\\|", 3);
            if (parts.length == 3) {
                String voiceId = parts[1];
                String encodedAudio = parts[2];

                // 解码音频数据 (在后台线程中进行)
                new Thread(() -> {
                    try {
                        byte[] audioData = Base64.getDecoder().decode(encodedAudio);
                        voiceMessages.put(voiceId, audioData); // 确保语音消息被存储

                        SwingUtilities.invokeLater(() -> {
                            // 显示带播放按钮的语音消息（使用默认发送者）
                            logVoiceMessage("其他用户", voiceId, false);
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> log("解析语音消息失败: " + e.getMessage() + "\n"));
                    }
                }).start();
            }
        }
        // 处理图片信息
        else if (message.startsWith("/image_info|")) {
            System.out.println("收到图片信息: " + message);
            puh.processImageInfo(message, logArea);
        }
        // 处理图片块
        else if (message.startsWith("/image_chunk|")) {
            // 只打印前50个字符以避免日志过长
            System.out.println("收到图片块: " + message.substring(0, Math.min(message.length(), 50)) + "...");
            puh.processImageChunk(message, logArea, receivedImages, this);
        }
        // 处理点对点消息
        else if (message.startsWith("/p2p_msg|")) {
            // 格式: /p2p_msg|sender|senderPassword|content
            String[] parts = message.split("\\|", 4);
            if (parts.length == 4) {
                String sender = parts[1];
                String senderPassword = parts[2];
                String content = parts[3];
                // 查找或创建点对点聊天窗口
                SwingUtilities.invokeLater(() -> handleP2PMessage(sender, senderPassword, content));
            }
        }
        // 处理点对点通知
        else if (message.startsWith("/p2p_notification|")) {
            // 格式: /p2p_notification|sender|senderPassword
            String[] parts = message.split("\\|", 3);
            if (parts.length == 3) {
                String sender = parts[1];
                String senderPassword = parts[2];
                SwingUtilities.invokeLater(() -> handleP2PNotification(sender, senderPassword));
            }
        }
        // 处理点对点验证结果
        else if (message.startsWith("/p2p_verify_result|")) {
            // 格式: /p2p_verify_result|success 或 /p2p_verify_result|error|errorMessage
            String[] parts = message.split("\\|", 3);
            if (parts.length >= 2) {
                String result = parts[1];
                if ("success".equals(result)) {
                    SwingUtilities.invokeLater(() -> handleP2PVerifySuccess());
                } else if (parts.length == 3) {
                    String errorMessage = parts[2];
                    SwingUtilities.invokeLater(() -> handleP2PVerifyError(errorMessage));
                }
            }
        }
        // 处理点对点错误
        else if (message.startsWith("/p2p_error|")) {
            String error = message.substring(11); // 提取错误信息
            SwingUtilities.invokeLater(() -> handleP2PError(error));
        }
        // 处理DeepSeek回答
        else if (message.startsWith("/deepseek_answer|")) {
            String answer = message.substring(18); // 提取回答内容
            // 超过45字符自动换行，作为单条消息显示
            String wrappedAnswer = wrapText(answer, 45);
            SwingUtilities.invokeLater(() -> {
                log("[DeepSeek AI]\n" + wrappedAnswer + "\n");
            });
        } else {
            // 处理普通文本消息
            log(message + "\n");
        }
    }
    
    // 更新在线用户列表
    private void updateOnlineUsers(String userList) {
        onlineUsers.clear();
        if (!userList.isEmpty()) {
            String[] users = userList.split(",");
            for (String user : users) {
                if (!user.trim().isEmpty()) {
                    onlineUsers.add(user.trim());
                }
            }
        }
        // 更新UI显示
        refreshOnlineUsersPanel();
    }
    
    // 显示自己的点对点密码
    private void showMyP2PPassword() {
        if (myP2PPassword != null) {
            // 更新密码标签
            if (p2pPasswordLabel != null) {
                p2pPasswordLabel.setText("我的点对点密码: " + myP2PPassword);
            }
            // 日志输出
            log("[系统] 您的点对点聊天密码是: " + myP2PPassword + "\n");
        }
    }
    
    // 刷新在线用户面板
    private void refreshOnlineUsersPanel() {
        SwingUtilities.invokeLater(() -> {
            onlineUsersPanel.removeAll();
            for (String user : onlineUsers) {
                if (user.equals(currentUsername)) {
                    // 不显示自己，或者显示为特殊标记
                    continue;
                }
                JButton userButton = new JButton(user);
                userButton.setAlignmentX(Component.LEFT_ALIGNMENT);
                userButton.addActionListener(e -> startP2PChat(user));
                onlineUsersPanel.add(userButton);
                onlineUsersPanel.add(Box.createRigidArea(new Dimension(0, 5)));
            }
            onlineUsersPanel.revalidate();
            onlineUsersPanel.repaint();
        });
    }
    
    // 启动点对点聊天
    private void startP2PChat(String targetUser) {
        String password = JOptionPane.showInputDialog(this, "请输入用户 " + targetUser + " 的点对点聊天密码:", "密码输入", JOptionPane.QUESTION_MESSAGE);
        if (password == null || password.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "无效验证码", "错误", JOptionPane.ERROR_MESSAGE);
            return; // 用户取消或输入空密码
        }
        password = password.trim();
        
        // 存储待处理的请求
        pendingP2PTargetUser = targetUser;
        pendingP2PPassword = password;
        
        // 发送验证请求到服务器
        if (out != null) {
            out.println("/p2p_verify|" + password);
            log("[系统] 正在验证点对点密码...\n");
        } else {
            JOptionPane.showMessageDialog(this, "未连接到服务器", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    // 打开点对点聊天窗口
    private void openP2PChatWindow(String targetUser, String password) {
        // 如果窗口已存在，则将其带到前台
        if (p2pWindows.containsKey(targetUser)) {
            P2PChatWindow existing = p2pWindows.get(targetUser);
            // 检查窗口是否已处置
            if (!existing.isDisplayable()) {
                // 窗口已处置，从映射中移除并创建新窗口
                p2pWindows.remove(targetUser);
            } else {
                // 窗口可能被隐藏了（默认关闭行为是HIDE_ON_CLOSE），需要确保可见
                existing.setVisible(true);
                existing.toFront();
                existing.requestFocus();
                return;
            }
        }
        P2PChatWindow window = new P2PChatWindow(targetUser, password);
        window.setVisible(true);
        p2pWindows.put(targetUser, window);
        // 窗口关闭时从映射中移除
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                p2pWindows.remove(targetUser);
            }
        });
    }
    
    // 处理接收到的点对点消息
    private void handleP2PMessage(String sender, String senderPassword, String content) {
        // 查找或创建点对点聊天窗口
        if (!p2pWindows.containsKey(sender)) {
            // 自动打开新窗口，使用发送者的密码作为目标密码
            openP2PChatWindow(sender, senderPassword);
        }
        P2PChatWindow window = p2pWindows.get(sender);
        if (window != null) {
            window.receiveMessage(sender, content);
        }
    }
    
    // 处理点对点错误
    private void handleP2PError(String error) {
        log("[系统] 点对点聊天错误: " + error + "\n");
        JOptionPane.showMessageDialog(this, error, "点对点聊天错误", JOptionPane.WARNING_MESSAGE);
    }
    
    // 发送消息逻辑
    private class SendListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            if (!isConnected) {
                JOptionPane.showMessageDialog(ChatClient.this, "请先连接服务器！", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String message = inputField.getText().trim();
            if (message.isEmpty()) return;

            // 检查是否是发送图片命令
            if (message.equals("/sendimage")) {
                puh.sendImageMessage(inputField, out, currentUsername, 
                                   lastImageSendTime, IMAGE_SEND_INTERVAL, MAX_IMAGE_SIZE,
                                   () -> lastImageSendTime = System.currentTimeMillis());
                return;
            }

            // 检查消息字节长度
            try {
                byte[] messageBytes = message.getBytes("UTF-8");
                if (messageBytes.length > MAX_MESSAGE_BYTES) {
                    // 超出字节限制，弹出警告窗口
                    JOptionPane.showMessageDialog(ChatClient.this,
                        "警告：超出字节长度\n最大允许: " + MAX_MESSAGE_BYTES + " 字节\n当前消息: " + messageBytes.length + " 字节",
                        "警告", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            } catch (Exception ex) {
                // 编码异常处理
                JOptionPane.showMessageDialog(ChatClient.this,
                    "消息编码错误: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 检查消息发送限制
            namelog.SendMessageResult result = namelog.canSendMessage(currentUsername, message);
            if (!result.isAllowed()) {
                JOptionPane.showMessageDialog(ChatClient.this, result.getMessage(), "发送失败", JOptionPane.WARNING_MESSAGE);
                log("消息发送被拒绝: " + result.getMessage() + "\n");
                return;
            }

            out.println(message);  // 发送到服务器
            lastRealMessageSentTime = System.currentTimeMillis();
            String logText = "我: " + message + "\n";  // 本地显示自己发送的消息
            log(logText);
            inputField.setText("");
            inputField.requestFocus();
            updateByteCount(); // 更新字节计数显示
        }
    }

    // 断开连接
    private void disconnect() {
        try {
            // 停止心跳机制
            stopHeartbeat();
            
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
            isConnected = false;
            SwingUtilities.invokeLater(() -> {
                log("已断开连接");
                connectBtn.setText("连接");
                // 重置聊天记录加载标志，以便下次连接时重新加载
                isChatHistoryLoaded = false;
            });
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    // 错误提示
    private void showError(String msg) {
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(ChatClient.this, msg, "错误", JOptionPane.ERROR_MESSAGE)
        );
    }

    // 日志输出方法
    private void log(String text) {
        // 保存聊天记录到文件 (在后台线程中进行)
        new Thread(() -> {
            saveChatHistory(text);
        }).start();
        
        // 在EDT中更新UI
        SwingUtilities.invokeLater(() -> {
            // 创建一个水平面板来显示消息
            JPanel messageRow = new JPanel();
            messageRow.setLayout(new FlowLayout(FlowLayout.LEFT));
            messageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // 添加消息文本
            JLabel messageLabel = new JLabel(text);
            messageLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
            messageRow.add(messageLabel);
            
            // 添加到消息面板
            messagePanel.add(messageRow);
            
            // 重新验证和重绘面板
            messagePanel.revalidate();
            messagePanel.repaint();
            
            // 滚动到最新消息
            messageScrollPane.getVerticalScrollBar().setValue(messageScrollPane.getVerticalScrollBar().getMaximum());
        });
    }
    
    // 专门用于添加带查看按钮的图片消息
    public void logImageMessage(String fileName, String imageId) {
        SwingUtilities.invokeLater(() -> {
            // 创建一个水平面板来显示消息
            JPanel messageRow = new JPanel();
            messageRow.setLayout(new FlowLayout(FlowLayout.LEFT));
            messageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // 添加消息文本
            JLabel messageLabel = new JLabel("[系统] 图片接收完成: " + fileName);
            messageLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
            messageRow.add(messageLabel);
            
            // 添加查看按钮
            JButton viewButton = new JButton("查看");
            viewButton.addActionListener(e -> {
                String imageData = receivedImages.get(imageId);
                if (imageData != null) {
                    lookph.displayImage(imageData, fileName);
                } else {
                    JOptionPane.showMessageDialog(ChatClient.this, "图片数据不存在", "错误", JOptionPane.ERROR_MESSAGE);
                }
            });
            messageRow.add(viewButton);
            
            // 添加到消息面板
            messagePanel.add(messageRow);
            
            // 重新验证和重绘面板
            messagePanel.revalidate();
            messagePanel.repaint();
            
            // 滚动到最新消息
            messageScrollPane.getVerticalScrollBar().setValue(messageScrollPane.getVerticalScrollBar().getMaximum());
        });
    }
    
    // 专门用于添加带播放按钮的语音消息
    public void logVoiceMessage(String sender, String voiceId, boolean isOwnMessage) {
        SwingUtilities.invokeLater(() -> {
            // 创建一个水平面板来显示消息
            JPanel messageRow = new JPanel();
            messageRow.setLayout(new FlowLayout(FlowLayout.LEFT));
            messageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // 添加消息文本
            String prefix = isOwnMessage ? "我" : sender;
            JLabel messageLabel = new JLabel(prefix + ": [语音消息] ");
            messageLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
            messageRow.add(messageLabel);
            
            // 添加播放按钮
            JButton playButton = new JButton("播放");
            playButton.addActionListener(e -> {
                if (voiceMessages.containsKey(voiceId)) {
                    playVoiceMessage(voiceId);
                } else {
                    JOptionPane.showMessageDialog(ChatClient.this, "语音数据不存在或已过期", "错误", JOptionPane.ERROR_MESSAGE);
                }
            });
            messageRow.add(playButton);
            
            // 添加到消息面板
            messagePanel.add(messageRow);
            
            // 重新验证和重绘面板
            messagePanel.revalidate();
            messagePanel.repaint();
            
            // 滚动到最新消息
            messageScrollPane.getVerticalScrollBar().setValue(messageScrollPane.getVerticalScrollBar().getMaximum());
        });
    }
    
    public static void main(String[] args) {
        // 检查程序是否已经运行
        if (!acquireLock()) {
            JOptionPane.showMessageDialog(null, "程序已经在运行中，请勿多开！", "禁止多开", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        SwingUtilities.invokeLater(() -> new ChatClient());
    }
    
    /**
     * 获取程序锁，防止多开
     * @return 如果成功获取锁返回true，否则返回false
     */
    private static boolean acquireLock() {
        try {
            // 创建锁文件路径（使用系统临时目录）
            String lockFilePath = System.getProperty("java.io.tmpdir") + File.separator + LOCK_FILE_NAME;
            File lockFile = new File(lockFilePath);
            
            // 创建文件通道
            lockChannel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            );
            
            // 尝试获取独占锁
            applicationLock = lockChannel.tryLock();
            
            // 如果锁获取失败，说明程序已经在运行
            if (applicationLock == null) {
                lockChannel.close();
                return false;
            }
            
            // 添加关闭钩子，确保程序正常退出时释放锁
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseLock();
            }));
            
            return true;
        } catch (Exception e) {
            // 发生异常时，假设程序已经在运行
            try {
                if (lockChannel != null) {
                    lockChannel.close();
                }
            } catch (IOException ioException) {
                // 忽略关闭异常
            }
            return false;
        }
    }
    
    /**
     * 释放程序锁
     */
    private static void releaseLock() {
        try {
            if (applicationLock != null) {
                applicationLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException e) {
            // 忽略释放锁时的异常
        }
    }
    
    // 在类的字段声明部分添加以下内容
    private AudioFormat getSupportedFormat() {
        // 定义一系列需要测试的格式，从最可能支持的开始
        AudioFormat[] testFormats = {
            new AudioFormat(8000, 16, 1, true, false),   // 8kHz, 16位, 单声道
            new AudioFormat(16000, 16, 1, true, false),  // 16kHz, 16位, 单声道
            new AudioFormat(22050, 16, 1, true, false),  // 22.05kHz, 16位, 单声道
            new AudioFormat(11025, 8, 1, true, false),   // 11.025kHz, 8位, 单声道
            new AudioFormat(44100, 16, 1, true, false),  // 44.1kHz, 16位, 单声道
            new AudioFormat(44100, 16, 2, true, false)   // 44.1kHz, 16位, 立体声
        };

        // 测试每个格式是否真正可用
        for (AudioFormat format : testFormats) {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (AudioSystem.isLineSupported(info)) {
                    // 进一步尝试实际打开线路来确认格式是否真正支持
                    TargetDataLine testLine = (TargetDataLine) AudioSystem.getLine(info);
                    testLine.open(format);
                    testLine.close();
                    return format; // 如果成功打开和关闭，说明格式可用
                }
            } catch (Exception e) {
                // 格式不支持，继续测试下一个
                continue;
            }
        }

        return null; // 没有找到支持的格式
    }

    // 添加音频支持检查方法
    private void checkAudioSupport() {
        try {
            // 检查是否有音频混音器
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            if (mixerInfos.length == 0) {
                log("系统未检测到音频设备\n");
                disableVoiceFeature();
                return;
            }
            log("检测到 " + mixerInfos.length + " 个音频设备\n");

            // 尝试获取支持的格式
            AudioFormat format = getSupportedFormat();
            if (format == null) {
                log("系统不支持标准音频格式，语音功能将被禁用\n");
                disableVoiceFeature();
                return;
            }

            log("使用音频格式: " + format.toString() + "\n");

            // 检查录音支持
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(targetInfo)) {
                log("系统不支持录音功能，语音功能将被禁用\n");
                log("请求的格式: " + format.toString() + "\n");
                disableVoiceFeature();
                return;
            }

            log("音频系统检查通过，语音功能可用\n");
        } catch (Exception e) {
            log("音频系统检查失败: " + e.getMessage() + "，语音功能将被禁用\n");
            disableVoiceFeature();
        }
    }

    // 添加禁用语音功能的方法
    private void disableVoiceFeature() {
        SwingUtilities.invokeLater(() -> {
            if (voiceBtn != null) {
                voiceBtn.setEnabled(false);
                voiceBtn.setText("语音功能不可用");
            }
        });
    }

    // 添加获取聊天记录文件路径的方法
    private String getChatHistoryFilePath() {
        String userHome = System.getProperty("user.home");
        String hiddenDirPath = userHome + File.separator + ".chatspeak";
        File hiddenDir = new File(hiddenDirPath);
        if (!hiddenDir.exists()) {
            hiddenDir.mkdirs();
        }
        // 每个群组一个聊天记录文件
        return hiddenDirPath + File.separator + "chat_history_" + currentUserGroup + ".txt";
    }

    // 添加加载聊天记录的方法
    private void loadChatHistory() {
        // 根据新需求，不再加载本地聊天记录，只显示服务器发送的历史记录
        // 确保只加载一次本地聊天记录
        if (isChatHistoryLoaded) {
            return;
        }

        // 设置加载标志，防止重复调用
        isChatHistoryLoaded = true;

        // 不再加载本地聊天记录，只等待服务器发送历史记录
        SwingUtilities.invokeLater(() -> {
            // 处理暂存的消息
            processPendingMessages();
        });
    }

    // 添加保存聊天记录的方法
    private void saveChatHistory(String text) {
        try {
            // 确保聊天记录文件路径已初始化
            if (chatHistoryFilePath == null) {
                chatHistoryFilePath = getChatHistoryFilePath();
            }

            // 使用追加模式写入文件
            try (PrintWriter writer = new PrintWriter(new FileWriter(chatHistoryFilePath, true))) {
                // 移除文本末尾的换行符后再保存
                String line = text;
                if (line.endsWith("\n")) {
                    line = line.substring(0, line.length() - 1);
                }
                writer.println(line);
            }
        } catch (IOException e) {
            // 静默处理错误，不影响正常聊天功能
        }
    }

    // 添加处理暂存消息的方法
    private void processPendingMessages() {
        synchronized (pendingMessages) {
            for (String message : pendingMessages) {
                if (message.startsWith("/voice|")) {
                    String[] parts = message.split("\\|", 3);
                    if (parts.length == 3) {
                        String voiceId = parts[1];
                        String encodedAudio = parts[2];

                        // 解码音频数据 (在后台线程中进行)
                        new Thread(() -> {
                            try {
                                byte[] audioData = Base64.getDecoder().decode(encodedAudio);
                                voiceMessages.put(voiceId, audioData); // 确保语音消息被存储

                                SwingUtilities.invokeLater(() -> {
                                    // 显示语音消息
                                    String logText = "[" + voiceId + "] [语音消息已接收]\n";
                                    log(logText);
                                    playVoiceMessage(voiceId);
                                });
                            } catch (Exception e) {
                                SwingUtilities.invokeLater(() -> log("解析语音消息失败: " + e.getMessage() + "\n"));
                            }
                        }).start();
                    }
                } else {
                    // 处理普通文本消息
                    SwingUtilities.invokeLater(() -> log(message + "\n"));
                }
            }
            pendingMessages.clear(); // 清空暂存消息
        }
    }

    // 添加播放命令处理方法
    private void handlePlayCommand(String voiceId) {
        log("正在尝试播放语音消息: " + voiceId + "\n");
        playVoiceMessage(voiceId);
    }

    // 添加字节限制
    private static final int MAX_MESSAGE_BYTES = 600;

    // 添加字节计数标签
    private JLabel byteCountLabel;

    // 添加更新字节计数的方法
    private void updateByteCount() {
        SwingUtilities.invokeLater(() -> {
            try {
                String text = inputField.getText();
                int byteCount = text.getBytes("UTF-8").length;
                byteCountLabel.setText(byteCount + "/" + MAX_MESSAGE_BYTES + " 字节");

                // 根据字节长度改变标签颜色
                if (byteCount > MAX_MESSAGE_BYTES) {
                    byteCountLabel.setForeground(Color.RED);
                } else if (byteCount > MAX_MESSAGE_BYTES * 0.8) { // 超过80%时变橙色警告
                    byteCountLabel.setForeground(Color.ORANGE);
                } else {
                    byteCountLabel.setForeground(Color.BLACK);
                }
            } catch (Exception e) {
                byteCountLabel.setText("计算错误");
                byteCountLabel.setForeground(Color.RED);
            }
        });
    }

    // 添加一个标志位，确保历史记录只加载一次
    private boolean isChatHistoryLoaded = false;

    // 添加定时任务用于清理过期的图片接收器
    private javax.swing.Timer cleanupTimer;

    // 添加测试图片显示的方法
    private void testImageDisplay() {
        // 创建一个测试按钮
        JButton testImageButton = new JButton("测试图片显示");
        testImageButton.addActionListener(e -> {
            try {
                // 创建一个简单的测试图片（红色填充的图片）
                BufferedImage testImage = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = testImage.createGraphics();
                g.setColor(Color.RED);
                g.fillRect(0, 0, 200, 200);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Arial", Font.BOLD, 20));
                g.drawString("Test Image", 50, 100);
                g.dispose();

                // 转换为ImageIcon并显示
                ImageIcon icon = new ImageIcon(testImage);
                JFrame frame = new JFrame("测试图片显示");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.add(new JLabel(icon));
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);

                log("测试图片显示成功\n");
            } catch (Exception ex) {
                log("测试图片显示失败: " + ex.getMessage() + "\n");
                ex.printStackTrace();
            }
        });

        // 将测试按钮添加到顶部面板
        JPanel topPanel = (JPanel) getContentPane().getComponent(0);
        topPanel.add(testImageButton);
        topPanel.revalidate();
    }

    // 实现ImageMessageCallback接口
    @Override
    public void onImageReceived(String fileName, String imageId) {
        logImageMessage(fileName, imageId);
    }
    
    // 在ChatClient类中添加心跳相关字段
    private javax.swing.Timer heartbeatTimer;
    
    // 启动心跳定时器
    private void startHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.stop();
        }
        
        heartbeatTimer = new javax.swing.Timer(20000, e -> { // 每20秒检查一次
            if (isConnected && out != null) {
                // 只在最近20秒内没有发送过真实消息时才发ping
                // 防止持续发送/ping，同时保证空闲时连接不被断开
                if (System.currentTimeMillis() - lastRealMessageSentTime >= 20000) {
                    out.println("/ping");
                }
            }
        });
        heartbeatTimer.start();
    }
    
    // 停止心跳定时器
    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.stop();
            heartbeatTimer = null;
        }
    }
    
    // 点对点聊天窗口内部类
    private class P2PChatWindow extends JFrame {
        private String targetUser;
        private String targetPassword;
        private JTextArea messageArea;
        private JTextField inputField;
        private JButton sendButton;
        
        public P2PChatWindow(String targetUser, String targetPassword) {
            this.targetUser = targetUser;
            this.targetPassword = targetPassword;
            initUI();
        }
        
        private void initUI() {
            setTitle("私聊 - " + targetUser);
            setSize(400, 300);
            setLocationRelativeTo(ChatClient.this);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());
            
            messageArea = new JTextArea();
            messageArea.setEditable(false);
            add(new JScrollPane(messageArea), BorderLayout.CENTER);
            
            JPanel bottomPanel = new JPanel(new BorderLayout());
            inputField = new JTextField();
            sendButton = new JButton("发送");
            sendButton.addActionListener(e -> sendP2PMessage());
            inputField.addActionListener(e -> sendP2PMessage());
            
            bottomPanel.add(inputField, BorderLayout.CENTER);
            bottomPanel.add(sendButton, BorderLayout.EAST);
            add(bottomPanel, BorderLayout.SOUTH);
        }
        
        private void sendP2PMessage() {
            String message = inputField.getText().trim();
            if (message.isEmpty()) return;
            if (out != null) {
                // 发送点对点消息格式: /p2p|targetPassword|message
                out.println("/p2p|" + targetPassword + "|" + message);
                lastRealMessageSentTime = System.currentTimeMillis();
                messageArea.append("我: " + message + "\n");
                inputField.setText("");
            }
        }
        
        public void receiveMessage(String sender, String message) {
            messageArea.append(sender + ": " + message + "\n");
        }
    }
    
    // 处理点对点通知
    private void handleP2PNotification(String sender, String senderPassword) {
        // 显示弹窗通知，提供打开聊天窗口的选项
        int option = JOptionPane.showConfirmDialog(this, 
            "用户 " + sender + " 想要与您私聊。是否打开聊天窗口？", 
            "私聊请求", 
            JOptionPane.YES_NO_OPTION,
            JOptionPane.INFORMATION_MESSAGE);
        
        if (option == JOptionPane.YES_OPTION) {
            openP2PChatWindow(sender, senderPassword);
        }
    }
    
    // 处理点对点验证成功
    private void handleP2PVerifySuccess() {
        if (pendingP2PTargetUser != null && pendingP2PPassword != null) {
            // 验证成功，打开私聊窗口
            openP2PChatWindow(pendingP2PTargetUser, pendingP2PPassword);
            log("[系统] 密码验证成功，已打开私聊窗口\n");
            
            // 清空待处理请求
            pendingP2PTargetUser = null;
            pendingP2PPassword = null;
        }
    }
    
    // 处理点对点验证错误
    private void handleP2PVerifyError(String errorMessage) {
        JOptionPane.showMessageDialog(this, 
            "无效验证码: " + errorMessage, 
            "验证失败", 
            JOptionPane.ERROR_MESSAGE);
        log("[系统] 密码验证失败: " + errorMessage + "\n");
        
        // 清空待处理请求
        pendingP2PTargetUser = null;
        pendingP2PPassword = null;
    }
    
    /**
     * 将文本按指定字符数换行，尽量在空格处断开
     */
    private String wrapText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            // 如果不是最后一段，尝试在空格处断开
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(text.substring(start, end));
            start = end;
            // 跳过空格
            while (start < text.length() && text.charAt(start) == ' ') {
                start++;
            }
        }
        return result.toString();
    }
}
