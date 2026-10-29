package minichat_ok.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

import minichat_ok.server.ChatServer;

/**
 * Giao diện Swing của phía Server.
 *
 * <p>Cho phép nhập cổng, khởi động/dừng server và theo dõi nhật ký kết nối.
 * Mọi cập nhật giao diện từ luồng nền đều được chuyển về EDT.</p>
 */
@SuppressWarnings("this-escape")
public class ServerFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_PORT = 5000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color COLOR_RUNNING = new Color(0, 128, 0);
    private static final Color COLOR_STOPPED = new Color(180, 0, 0);

    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 6);
    private final JButton startButton = new JButton("Khởi động");
    private final JButton stopButton = new JButton("Dừng");
    private final JLabel statusLabel = new JLabel("Chưa khởi động");
    private final JTextArea logArea = new JTextArea();

    /** Danh sách yêu cầu kết nối đang chờ duyệt (mỗi yêu cầu một hàng có nút). */
    private final JPanel joinListPanel = new JPanel();
    private final JScrollPane joinScrollPane = new JScrollPane(joinListPanel);
    private final JLabel joinCountLabel = new JLabel("Đang chờ duyệt: 0");

    private transient ChatServer server;

    public ServerFrame() {
        super("MiniChat - Server");
        initComponents();
    }

    private void initComponents() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        controlPanel.setBorder(BorderFactory.createTitledBorder("Điều khiển Server"));
        controlPanel.add(new JLabel("Cổng:"));
        controlPanel.add(portField);
        controlPanel.add(startButton);
        controlPanel.add(stopButton);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        statusPanel.add(new JLabel("Trạng thái:"));
        statusLabel.setForeground(COLOR_STOPPED);
        statusPanel.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlPanel, BorderLayout.NORTH);
        topPanel.add(statusPanel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Nhật ký kết nối"));
        add(scrollPane, BorderLayout.CENTER);

        joinListPanel.setLayout(new BoxLayout(joinListPanel, BoxLayout.Y_AXIS));
        joinListPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        joinScrollPane.setBorder(BorderFactory.createTitledBorder("Yêu cầu kết nối"));
        joinScrollPane.setPreferredSize(new Dimension(300, 0));
        joinCountLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));

        JPanel joinPanel = new JPanel(new BorderLayout());
        joinPanel.add(joinScrollPane, BorderLayout.CENTER);
        joinPanel.add(joinCountLabel, BorderLayout.SOUTH);
        add(joinPanel, BorderLayout.EAST);

        refreshJoinRequests(List.of());

        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setPreferredSize(new Dimension(920, 480));
        setMinimumSize(new Dimension(700, 380));
        pack();
        setLocationRelativeTo(null);
    }

    private void startServer() {
        String portText = portField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showError("Cổng phải là số nguyên trong khoảng 1..65535.");
            return;
        }
        if (port < 1 || port > 65535) {
            showError("Cổng phải nằm trong khoảng 1..65535.");
            return;
        }

        ChatServer newServer = new ChatServer(this::appendLog);
        newServer.setClientCountListener(count -> refreshStatus());
        newServer.setGroupCountListener(count -> refreshStatus());
        newServer.setAutoApproveJoins(false);
        newServer.setJoinRequestListener(this::refreshJoinRequests);
        try {
            newServer.start(port);
        } catch (IOException | RuntimeException e) {
            appendLog("Không thể khởi động Server trên cổng " + port + ": " + e.getMessage());
            showError("Không thể khởi động Server trên cổng " + port + ".\n"
                    + "Cổng có thể đang bị chương trình khác sử dụng.\n"
                    + "Chi tiết: " + e.getMessage());
            return;
        }

        server = newServer;
        portField.setEnabled(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("Đang chạy - cổng " + port + " - 0 client - 0 nhóm", COLOR_RUNNING);
        setTitle("MiniChat - Server [cổng " + port + "]");
    }

    /**
     * Được gọi từ luồng server khi số client hoặc số nhóm thay đổi; luôn cập nhật trên EDT.
     */
    private void refreshStatus() {
        SwingUtilities.invokeLater(() -> {
            ChatServer current = server;
            if (current == null || !current.isRunning()) {
                return;
            }
            setStatus("Đang chạy - cổng " + current.getPort() + " - " + current.getClientCount()
                    + " client - " + current.getGroupCount() + " nhóm - "
                    + current.getPendingJoinCount() + " chờ duyệt", COLOR_RUNNING);
        });
    }

    /**
     * Cập nhật danh sách yêu cầu kết nối đang chờ duyệt.
     *
     * <p>{@link ChatServer} gọi phương thức này từ luồng xử lý client nên mọi thay
     * đổi giao diện đều được chuyển về EDT.</p>
     *
     * @param requests danh sách yêu cầu hiện tại (chỉ đọc)
     */
    private void refreshJoinRequests(List<ChatServer.JoinRequest> requests) {
        if (!SwingUtilities.isEventDispatchThread()) {
            List<ChatServer.JoinRequest> snapshot = List.copyOf(requests);
            SwingUtilities.invokeLater(() -> rebuildJoinList(snapshot));
            return;
        }
        rebuildJoinList(requests);
    }

    private void rebuildJoinList(List<ChatServer.JoinRequest> requests) {
        joinListPanel.removeAll();
        if (requests.isEmpty()) {
            JLabel emptyLabel = new JLabel("Chưa có yêu cầu kết nối nào.");
            emptyLabel.setForeground(new Color(110, 110, 110));
            emptyLabel.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
            joinListPanel.add(emptyLabel);
        } else {
            for (ChatServer.JoinRequest request : requests) {
                joinListPanel.add(createJoinRow(request));
                joinListPanel.add(Box.createVerticalStrut(6));
            }
        }
        joinListPanel.add(Box.createVerticalGlue());

        joinCountLabel.setText("Đang chờ duyệt: " + requests.size());
        joinListPanel.revalidate();
        joinListPanel.repaint();
        refreshStatus();
    }

    /**
     * Tạo một hàng trong danh sách chờ duyệt: tên client, địa chỉ và hai nút.
     */
    private JPanel createJoinRow(ChatServer.JoinRequest request) {
        JLabel nameLabel = new JLabel(request.getClientName());
        nameLabel.setFont(nameLabel.getFont().deriveFont(
                Font.BOLD, nameLabel.getFont().getSize2D() + 1f));
        JLabel addressLabel = new JLabel(request.getClientAddress());
        addressLabel.setForeground(new Color(90, 90, 90));

        JPanel infoPanel = new JPanel();
        infoPanel.setOpaque(false);
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.add(nameLabel);
        infoPanel.add(addressLabel);

        JButton approveButton = new JButton("Duyệt");
        JButton blockButton = new JButton("Chặn");
        approveButton.setToolTipText("Cho " + request.getClientName() + " vào phòng chat");
        blockButton.setToolTipText("Chặn " + request.getClientName() + " kết nối");
        approveButton.addActionListener(e -> decideJoin(request, true, approveButton, blockButton));
        blockButton.addActionListener(e -> decideJoin(request, false, approveButton, blockButton));

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 6, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(approveButton);
        buttonPanel.add(blockButton);

        JPanel row = new JPanel(new BorderLayout(4, 6));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(205, 205, 205)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.setBackground(new Color(250, 250, 250));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(infoPanel, BorderLayout.CENTER);
        row.add(buttonPanel, BorderLayout.SOUTH);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * Ghi nhận quyết định của người quản trị cho một yêu cầu kết nối.
     */
    private void decideJoin(ChatServer.JoinRequest request, boolean approved,
            JButton approveButton, JButton blockButton) {
        approveButton.setEnabled(false);
        blockButton.setEnabled(false);

        ChatServer current = server;
        boolean changed = current != null
                && (approved ? current.approveJoin(request) : current.denyJoin(request));
        if (!changed) {
            appendLog("Yêu cầu kết nối của " + request.getClientName()
                    + " không còn chờ duyệt (có thể đã hết thời gian chờ).");
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        refreshJoinRequests(List.of());
        portField.setEnabled(true);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        setStatus("Đã dừng", COLOR_STOPPED);
        setTitle("MiniChat - Server");
    }

    private void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        dispose();
        System.exit(0);
    }

    /**
     * Ghi nhật ký an toàn với Swing: luôn thực hiện trên EDT.
     */
    private void appendLog(String message) {
        String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator();
        if (SwingUtilities.isEventDispatchThread()) {
            appendLogOnEdt(line);
        } else {
            SwingUtilities.invokeLater(() -> appendLogOnEdt(line));
        }
    }

    private void appendLogOnEdt(String line) {
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Lỗi", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
                // Dùng giao diện mặc định của Java nếu không đổi được Look and Feel.
            }
            new ServerFrame().setVisible(true);
        });
    }
}
