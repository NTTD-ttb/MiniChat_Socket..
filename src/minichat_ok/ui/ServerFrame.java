package minichat_ok.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

import minichat_ok.server.ChatServer;

/**
 * Giao diện Swing của phía Server.
 *
 * <p>Cho phép nhập cổng, khởi động/dừng server và theo dõi nhật ký kết nối. Khung bên
 * phải có ba tab: <b>Chờ duyệt</b> (yêu cầu kết nối), <b>Người dùng</b> (client đã được
 * duyệt, có thể ngắt kết nối) và <b>Nhóm</b> (xem thành viên, thêm/xóa thành viên, đổi
 * tên hoặc xóa nhóm). Mọi cập nhật giao diện từ luồng nền đều được chuyển về EDT.</p>
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

    /** Khung bên phải: chờ duyệt, quản lý người dùng và quản lý nhóm. */
    private final JTabbedPane sideTabs = new JTabbedPane();

    /** Tab "Người dùng": client đã được duyệt và nút ngắt kết nối. */
    private final DefaultListModel<ChatServer.ClientInfo> clientModel = new DefaultListModel<>();
    private final JList<ChatServer.ClientInfo> clientList = new JList<>(clientModel);
    private final JLabel clientCountLabel = new JLabel("Đang kết nối: 0");
    private final JButton kickButton = new JButton("Ngắt kết nối");

    /** Tab "Nhóm": danh sách nhóm và thành viên của nhóm đang chọn. */
    private final DefaultListModel<ChatServer.GroupInfo> groupModel = new DefaultListModel<>();
    private final JList<ChatServer.GroupInfo> groupList = new JList<>(groupModel);
    private final DefaultListModel<String> memberModel = new DefaultListModel<>();
    private final JList<String> memberList = new JList<>(memberModel);
    private final JLabel groupCountLabel = new JLabel("Số nhóm: 0");
    private final JButton addMemberButton = new JButton("Thêm thành viên");
    private final JButton removeMemberButton = new JButton("Xóa thành viên");
    private final JButton renameGroupButton = new JButton("Đổi tên nhóm");
    private final JButton deleteGroupButton = new JButton("Xóa nhóm");

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

        sideTabs.addTab("Chờ duyệt", joinPanel);
        sideTabs.addTab("Người dùng", buildUsersPanel());
        sideTabs.addTab("Nhóm", buildGroupsPanel());
        sideTabs.setPreferredSize(new Dimension(430, 0));
        add(sideTabs, BorderLayout.EAST);

        clientList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateManageButtons();
            }
        });
        groupList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshSelectedGroupMembers();
            }
        });
        memberList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateManageButtons();
            }
        });

        refreshJoinRequests(List.of());
        resetAdminViews();

        stopButton.setEnabled(false);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        setPreferredSize(new Dimension(1180, 620));
        setMinimumSize(new Dimension(900, 520));
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
        newServer.setClientListListener(this::refreshClients);
        newServer.setGroupListListener(this::refreshGroups);
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
        updateManageButtons();
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

    /**
     * Tạo tab "Người dùng": danh sách client đã được duyệt và nút ngắt kết nối.
     */
    private JPanel buildUsersPanel() {
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.setCellRenderer(new ClientInfoRenderer());
        clientList.setVisibleRowCount(12);

        JScrollPane scroll = new JScrollPane(clientList);
        scroll.setBorder(BorderFactory.createTitledBorder("Client đang kết nối"));

        kickButton.setToolTipText("Đóng kết nối của client đang chọn");
        kickButton.addActionListener(e -> disconnectSelectedClient());

        JPanel bottom = new JPanel(new BorderLayout(6, 4));
        bottom.add(clientCountLabel, BorderLayout.NORTH);
        bottom.add(kickButton, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Tạo tab "Nhóm": danh sách nhóm, thành viên của nhóm đang chọn và các nút quản lý.
     */
    private JPanel buildGroupsPanel() {
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setCellRenderer(new GroupInfoRenderer());
        groupList.setVisibleRowCount(8);

        memberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        memberList.setVisibleRowCount(6);

        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setBorder(BorderFactory.createTitledBorder("Nhóm trên Server"));
        JScrollPane memberScroll = new JScrollPane(memberList);
        memberScroll.setBorder(BorderFactory.createTitledBorder("Thành viên nhóm đang chọn"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, groupScroll, memberScroll);
        split.setResizeWeight(0.55);
        split.setBorder(null);

        addMemberButton.addActionListener(e -> addMemberToSelectedGroup());
        removeMemberButton.addActionListener(e -> removeSelectedGroupMember());
        renameGroupButton.addActionListener(e -> renameSelectedGroup());
        deleteGroupButton.addActionListener(e -> deleteSelectedGroup());

        JPanel buttons = new JPanel(new GridLayout(2, 2, 6, 4));
        buttons.add(addMemberButton);
        buttons.add(removeMemberButton);
        buttons.add(renameGroupButton);
        buttons.add(deleteGroupButton);

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(groupCountLabel, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(split, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Cập nhật danh sách client đang kết nối; an toàn khi được gọi từ luồng server.
     */
    private void refreshClients(List<ChatServer.ClientInfo> clients) {
        if (!SwingUtilities.isEventDispatchThread()) {
            List<ChatServer.ClientInfo> snapshot = List.copyOf(clients);
            SwingUtilities.invokeLater(() -> rebuildClientList(snapshot));
            return;
        }
        rebuildClientList(clients);
    }

    private void rebuildClientList(List<ChatServer.ClientInfo> clients) {
        ChatServer.ClientInfo selected = clientList.getSelectedValue();
        String selectedName = (selected != null) ? selected.getName() : null;

        clientModel.clear();
        for (ChatServer.ClientInfo info : clients) {
            clientModel.addElement(info);
        }

        if (selectedName != null) {
            for (int i = 0; i < clientModel.size(); i++) {
                if (selectedName.equals(clientModel.get(i).getName())) {
                    clientList.setSelectedIndex(i);
                    break;
                }
            }
        }
        clientCountLabel.setText("Đang kết nối: " + clients.size());
        updateManageButtons();
    }

    /**
     * Cập nhật danh sách nhóm (kèm thành viên); an toàn khi được gọi từ luồng server.
     */
    private void refreshGroups(List<ChatServer.GroupInfo> groups) {
        if (!SwingUtilities.isEventDispatchThread()) {
            List<ChatServer.GroupInfo> snapshot = List.copyOf(groups);
            SwingUtilities.invokeLater(() -> rebuildGroupList(snapshot));
            return;
        }
        rebuildGroupList(groups);
    }

    private void rebuildGroupList(List<ChatServer.GroupInfo> groups) {
        ChatServer.GroupInfo selected = groupList.getSelectedValue();
        String selectedId = (selected != null) ? selected.getId() : null;

        groupModel.clear();
        int selectedIndex = -1;
        for (int i = 0; i < groups.size(); i++) {
            ChatServer.GroupInfo info = groups.get(i);
            groupModel.addElement(info);
            if (info.getId().equals(selectedId)) {
                selectedIndex = i;
            }
        }
        groupCountLabel.setText("Số nhóm: " + groups.size());
        if (selectedIndex >= 0) {
            groupList.setSelectedIndex(selectedIndex);
        }
        refreshSelectedGroupMembers();
    }

    /**
     * Nạp lại danh sách thành viên của nhóm đang chọn.
     */
    private void refreshSelectedGroupMembers() {
        ChatServer.GroupInfo selected = groupList.getSelectedValue();
        memberModel.clear();
        if (selected != null) {
            for (String member : selected.getMembers()) {
                memberModel.addElement(member);
            }
        }
        updateManageButtons();
    }

    /**
     * Xóa sạch hai tab quản lý khi server dừng hoặc chưa khởi động.
     */
    private void resetAdminViews() {
        clientModel.clear();
        groupModel.clear();
        memberModel.clear();
        clientList.clearSelection();
        groupList.clearSelection();
        clientCountLabel.setText("Đang kết nối: 0");
        groupCountLabel.setText("Số nhóm: 0");
        updateManageButtons();
    }

    /**
     * Bật/tắt các nút quản lý theo lựa chọn hiện tại và trạng thái server.
     */
    private void updateManageButtons() {
        ChatServer current = server;
        boolean running = current != null && current.isRunning();
        boolean hasGroup = groupList.getSelectedValue() != null;
        boolean hasMember = memberList.getSelectedValue() != null;

        kickButton.setEnabled(running && clientList.getSelectedValue() != null);
        addMemberButton.setEnabled(running && hasGroup);
        removeMemberButton.setEnabled(running && hasGroup && hasMember);
        renameGroupButton.setEnabled(running && hasGroup);
        deleteGroupButton.setEnabled(running && hasGroup);
    }

    /**
     * Ngắt kết nối client đang chọn ở tab "Người dùng".
     */
    private void disconnectSelectedClient() {
        ChatServer current = server;
        ChatServer.ClientInfo info = clientList.getSelectedValue();
        if (current == null || info == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Ngắt kết nối " + info.getName() + " (" + info.getAddress() + ")?",
                "Ngắt kết nối", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        String error = current.disconnectClient(info.getName());
        if (error != null) {
            showError(error);
        }
    }

    /**
     * Thêm một client đang kết nối vào nhóm đang chọn.
     */
    private void addMemberToSelectedGroup() {
        ChatServer current = server;
        ChatServer.GroupInfo group = groupList.getSelectedValue();
        if (current == null || group == null) {
            return;
        }

        List<String> candidates = new ArrayList<>();
        for (ChatServer.ClientInfo info : current.getConnectedClients()) {
            if (!group.getMembers().contains(info.getName())) {
                candidates.add(info.getName());
            }
        }
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Không còn client nào đang kết nối mà chưa thuộc nhóm " + group.getId() + ".",
                    "Thêm thành viên", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Object choice = JOptionPane.showInputDialog(this,
                "Chọn client để thêm vào nhóm " + group.getId() + ":", "Thêm thành viên",
                JOptionPane.QUESTION_MESSAGE, null, candidates.toArray(), candidates.get(0));
        if (choice == null) {
            return;
        }
        String error = current.addGroupMember(group.getId(), choice.toString());
        if (error != null) {
            showError(error);
        }
    }

    /**
     * Xóa thành viên đang chọn khỏi nhóm đang chọn.
     */
    private void removeSelectedGroupMember() {
        ChatServer current = server;
        ChatServer.GroupInfo group = groupList.getSelectedValue();
        String member = memberList.getSelectedValue();
        if (current == null || group == null || member == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Xóa " + member + " khỏi nhóm " + group.getId() + "?",
                "Xóa thành viên", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        String error = current.removeGroupMember(group.getId(), member);
        if (error != null) {
            showError(error);
        }
    }

    /**
     * Đổi tên hiển thị của nhóm đang chọn.
     */
    private void renameSelectedGroup() {
        ChatServer current = server;
        ChatServer.GroupInfo group = groupList.getSelectedValue();
        if (current == null || group == null) {
            return;
        }
        Object input = JOptionPane.showInputDialog(this,
                "Tên mới cho nhóm " + group.getId() + ":", "Đổi tên nhóm",
                JOptionPane.QUESTION_MESSAGE, null, null, group.getName());
        if (input == null) {
            return;
        }
        String error = current.renameGroup(group.getId(), input.toString());
        if (error != null) {
            showError(error);
        }
    }

    /**
     * Xóa hẳn nhóm đang chọn.
     */
    private void deleteSelectedGroup() {
        ChatServer current = server;
        ChatServer.GroupInfo group = groupList.getSelectedValue();
        if (current == null || group == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Xóa nhóm " + group.getId() + " (" + group.getName() + ")?\n"
                        + "Các thành viên sẽ được thông báo và nhóm biến mất khỏi danh sách của họ.",
                "Xóa nhóm", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        String error = current.deleteGroup(group.getId());
        if (error != null) {
            showError(error);
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        refreshJoinRequests(List.of());
        resetAdminViews();
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

    /**
     * Hiển thị một client trong tab "Người dùng": tên kèm địa chỉ {@code ip:port}.
     */
    private static final class ClientInfoRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ChatServer.ClientInfo info) {
                setText(info.getName() + "   " + info.getAddress());
                setToolTipText("Đang kết nối từ " + info.getAddress());
            }
            return this;
        }
    }

    /**
     * Hiển thị một nhóm trong tab "Nhóm": mã, tên và số thành viên.
     */
    private static final class GroupInfoRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ChatServer.GroupInfo info) {
                setText(info.getId() + "  ·  " + info.getName()
                        + "  (" + info.getMemberCount() + " thành viên)");
                setToolTipText("Người tạo: " + info.getOwner());
            }
            return this;
        }
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
