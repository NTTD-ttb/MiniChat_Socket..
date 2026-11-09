package minichat_ok.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import minichat_ok.common.Protocol;

/**
 * Máy chủ chat giai đoạn 3: một phòng chung, chat riêng 1-1 và chat nhóm.
 *
 * <p>Mỗi client kết nối được xử lý bởi một {@link ClientHandler} trên luồng riêng.
 * Server giữ danh sách client đang kết nối trong {@link CopyOnWriteArrayList} nên
 * việc thêm/xóa client luôn an toàn trong khi một luồng khác đang phát tin nhắn.
 * Tin nhắn công khai ({@code MSG}) được phát tới <b>tất cả</b> client, kể cả người
 * gửi. Tin nhắn riêng ({@code PRIV}) chỉ tới người gửi và người nhận. Tin nhắn nhóm
 * ({@code GMSG}) chỉ tới các thành viên của nhóm.</p>
 *
 * <p>Lớp này không phụ thuộc Swing: mọi thông báo được đẩy ra ngoài qua
 * {@link Consumer} nhận nhật ký do lớp giao diện truyền vào.</p>
 *
 * <p>Phần quản trị phục vụ giao diện Server: {@link #getConnectedClients()} và
 * {@link #disconnectClient(String)} cho tab người dùng; {@link #getGroups()},
 * {@link #addGroupMember(String, String)}, {@link #removeGroupMember(String, String)},
 * {@link #renameGroup(String, String)}, {@link #deleteGroup(String)} cho tab nhóm.
 * Giao diện đăng ký {@link #setClientListListener(Consumer)} /
 * {@link #setGroupListListener(Consumer)} để nhận ảnh chụp dữ liệu sau mỗi thay đổi.</p>
 */
public class ChatServer {

    private final Consumer<String> logger;
    private final List<ClientHandler> handlers = new CopyOnWriteArrayList<>();
    private final Map<String, ClientHandler> clientsByName = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final AtomicInteger clientCounter = new AtomicInteger();
    private final AtomicInteger groupCounter = new AtomicInteger();

    /** Các handler đã mở socket nhưng chưa được chấp nhận vào phòng. */
    private final List<ClientHandler> pendingHandlers = new CopyOnWriteArrayList<>();

    /** Các yêu cầu kết nối đang chờ người quản trị Server bấm Duyệt/Chặn. */
    private final List<JoinRequest> pendingJoins = new CopyOnWriteArrayList<>();

    /**
     * Khi {@code true} (mặc định), mọi yêu cầu kết nối được chấp nhận ngay mà không
     * cần giao diện. Giao diện Server đặt {@code false} để duyệt thủ công.
     */
    private volatile boolean autoApproveJoins = true;

    /** Thời gian tối đa (ms) một yêu cầu được chờ người quản trị quyết định. */
    private static final int JOIN_DECISION_TIMEOUT_MS = 60_000;

    /** Nơi nhận danh sách yêu cầu kết nối đang chờ (giao diện Server đăng ký). */
    private volatile Consumer<List<JoinRequest>> joinRequestListener = requests -> {
        // Mặc định: không ai quan tâm danh sách chờ duyệt.
    };

    /** Số thành viên tối đa của một nhóm (chỉ áp dụng khi tạo nhóm). */
    private static final int MAX_GROUP_MEMBERS = 20;

    /** Số nhóm tối đa một client có thể tham gia. */
    private static final int MAX_GROUPS_PER_CLIENT = 10;

    /** Khóa tuần tự hóa việc phát tin để mọi client nhận cùng một thứ tự. */
    private final Object broadcastLock = new Object();

    /** Nơi nhận số client đang kết nối (giao diện có thể đăng ký). */
    private volatile Consumer<Integer> clientCountListener = count -> {
        // Mặc định: không ai quan tâm số lượng client.
    };

    /** Nơi nhận số nhóm hiện có (giao diện Server có thể đăng ký). */
    private volatile Consumer<Integer> groupCountListener = count -> {
        // Mặc định: không ai quan tâm số lượng nhóm.
    };

    /** Nơi nhận danh sách client đang kết nối (giao diện quản lý người dùng). */
    private volatile Consumer<List<ClientInfo>> clientListListener = clients -> {
        // Mặc định: không ai quan tâm danh sách client.
    };

    /** Nơi nhận danh sách nhóm hiện có (giao diện quản lý nhóm). */
    private volatile Consumer<List<GroupInfo>> groupListListener = currentGroups -> {
        // Mặc định: không ai quan tâm danh sách nhóm.
    };

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    /**
     * @param logger nơi nhận nhật ký hoạt động (có thể là null, khi đó nhật ký bị bỏ qua)
     */
    public ChatServer(Consumer<String> logger) {
        this.logger = (logger != null) ? logger : message -> {
            // Không có nơi nhận nhật ký: bỏ qua.
        };
    }

    /**
     * Đăng ký nơi nhận số client mỗi khi danh sách thay đổi.
     *
     * @param listener hàm nhận số client hiện tại, có thể là null để bỏ đăng ký
     */
    public void setClientCountListener(Consumer<Integer> listener) {
        this.clientCountListener = (listener != null) ? listener : count -> {
            // Bỏ đăng ký.
        };
    }

    /**
     * Đăng ký nơi nhận số nhóm mỗi khi danh sách nhóm thay đổi.
     *
     * @param listener hàm nhận số nhóm hiện tại, có thể là null để bỏ đăng ký
     */
    public void setGroupCountListener(Consumer<Integer> listener) {
        this.groupCountListener = (listener != null) ? listener : count -> {
            // Bỏ đăng ký.
        };
    }

    /**
     * Đăng ký nơi nhận danh sách client đang kết nối (đã được duyệt). Danh sách được
     * gửi lại mỗi khi có client vào/rời phòng hoặc khi server dừng.
     *
     * @param listener hàm nhận danh sách (chỉ đọc), có thể là {@code null} để bỏ đăng ký
     */
    public void setClientListListener(Consumer<List<ClientInfo>> listener) {
        this.clientListListener = (listener != null) ? listener : clients -> {
            // Bỏ đăng ký.
        };
        notifyClientList();
    }

    /**
     * Đăng ký nơi nhận danh sách nhóm hiện có (kèm thành viên). Danh sách được gửi lại
     * mỗi khi nhóm được tạo/xóa/đổi tên hoặc thành viên thay đổi.
     *
     * @param listener hàm nhận danh sách (chỉ đọc), có thể là {@code null} để bỏ đăng ký
     */
    public void setGroupListListener(Consumer<List<GroupInfo>> listener) {
        this.groupListListener = (listener != null) ? listener : currentGroups -> {
            // Bỏ đăng ký.
        };
        notifyGroupList();
    }

    /**
     * Thông tin tóm tắt của một client đang kết nối, dùng cho giao diện quản lý người dùng.
     */
    public static final class ClientInfo {

        private final String name;
        private final String address;

        ClientInfo(String name, String address) {
            this.name = name;
            this.address = address;
        }

        /** @return tên định danh do Server cấp, ví dụ {@code "Client-1"} */
        public String getName() {
            return name;
        }

        /** @return địa chỉ {@code ip:port} của client */
        public String getAddress() {
            return address;
        }

        @Override
        public String toString() {
            return name + " (" + address + ")";
        }
    }

    /**
     * Thông tin tóm tắt của một nhóm, dùng cho giao diện quản lý nhóm.
     *
     * <p>Danh sách thành viên là bản sao tại thời điểm tạo đối tượng nên giao diện có
     * thể hiển thị mà không cần khóa.</p>
     */
    public static final class GroupInfo {

        private final String id;
        private final String name;
        private final String owner;
        private final List<String> members;

        GroupInfo(Group group) {
            this.id = group.getId();
            this.name = group.getName();
            this.owner = group.getOwner();
            this.members = List.copyOf(group.snapshotMembers());
        }

        /** @return mã nhóm, ví dụ {@code "G1"} */
        public String getId() {
            return id;
        }

        /** @return tên hiển thị của nhóm (quản trị viên có thể đổi) */
        public String getName() {
            return name;
        }

        /** @return tên client đã tạo nhóm */
        public String getOwner() {
            return owner;
        }

        /** @return danh sách thành viên (bản sao, chỉ đọc) */
        public List<String> getMembers() {
            return members;
        }

        /** @return số thành viên hiện tại */
        public int getMemberCount() {
            return members.size();
        }

        @Override
        public String toString() {
            return id + " - " + name;
        }
    }

    /**
     * Một yêu cầu kết nối đang chờ người quản trị Server quyết định.
     *
     * <p>Đối tượng được tạo trên luồng xử lý client và được đưa ra giao diện qua
     * {@link #setJoinRequestListener(Consumer)}. Giao diện gọi
     * {@link ChatServer#approveJoin(JoinRequest)} hoặc
     * {@link ChatServer#denyJoin(JoinRequest)} khi người quản trị bấm nút.</p>
     */
    public static final class JoinRequest {

        private final String clientName;
        private final String clientAddress;
        private final CountDownLatch decision = new CountDownLatch(1);
        private boolean approved;
        private boolean decided;

        JoinRequest(String clientName, String clientAddress) {
            this.clientName = clientName;
            this.clientAddress = clientAddress;
        }

        /** @return tên định danh Server dự kiến cấp cho client */
        public String getClientName() {
            return clientName;
        }

        /** @return địa chỉ {@code ip:port} của client */
        public String getClientAddress() {
            return clientAddress;
        }

        /** @return {@code true} nếu đã có quyết định (dù duyệt hay chặn) */
        public boolean isDecided() {
            synchronized (this) {
                return decided;
            }
        }

        /** @return {@code true} nếu yêu cầu đã được duyệt */
        public boolean isApproved() {
            synchronized (this) {
                return approved;
            }
        }

        boolean awaitDecision(long timeoutMs) throws InterruptedException {
            return decision.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /**
         * Ghi nhận quyết định.
         *
         * @return {@code false} nếu yêu cầu đã được quyết định trước đó
         */
        boolean decide(boolean accept) {
            synchronized (this) {
                if (decided) {
                    return false;
                }
                decided = true;
                approved = accept;
            }
            decision.countDown();
            return true;
        }
    }

    /**
     * Bật/tắt chế độ tự động chấp nhận mọi yêu cầu kết nối.
     *
     * <p>Mặc định là {@code true} để server không có giao diện (ví dụ kiểm thử raw
     * socket) vẫn hoạt động như trước. Giao diện Server gọi {@code false} để người
     * quản trị duyệt thủ công từng client.</p>
     *
     * @param auto {@code true} nếu muốn chấp nhận ngay mọi yêu cầu
     */
    public void setAutoApproveJoins(boolean auto) {
        this.autoApproveJoins = auto;
    }

    /**
     * Đăng ký nơi nhận danh sách yêu cầu kết nối đang chờ duyệt. Danh sách được gửi
     * lại mỗi khi có yêu cầu mới, có quyết định hoặc server dừng.
     *
     * @param listener hàm nhận danh sách (chỉ đọc), có thể là {@code null} để bỏ đăng ký
     */
    public void setJoinRequestListener(Consumer<List<JoinRequest>> listener) {
        this.joinRequestListener = (listener != null) ? listener : requests -> {
            // Bỏ đăng ký.
        };
        notifyPendingJoins();
    }

    /**
     * @return danh sách yêu cầu kết nối đang chờ duyệt (bản sao, chỉ đọc)
     */
    public List<JoinRequest> getPendingJoins() {
        return List.copyOf(pendingJoins);
    }

    /** @return số yêu cầu kết nối đang chờ duyệt */
    public int getPendingJoinCount() {
        return pendingJoins.size();
    }

    /**
     * Cho một client đang chờ vào phòng chat. Chỉ có tác dụng khi yêu cầu còn chờ.
     *
     * @param request yêu cầu lấy từ {@link #getPendingJoins()} hoặc listener
     * @return {@code true} nếu quyết định được ghi nhận
     */
    public boolean approveJoin(JoinRequest request) {
        return resolveJoin(request, true);
    }

    /**
     * Chặn một client đang chờ; client sẽ nhận {@code JOIN_DENY} và bị đóng kết nối.
     *
     * @param request yêu cầu lấy từ {@link #getPendingJoins()} hoặc listener
     * @return {@code true} nếu quyết định được ghi nhận
     */
    public boolean denyJoin(JoinRequest request) {
        return resolveJoin(request, false);
    }

    private boolean resolveJoin(JoinRequest request, boolean approved) {
        if (request == null || !pendingJoins.contains(request) || !request.decide(approved)) {
            return false;
        }
        log((approved ? "Chấp nhận" : "Từ chối") + " yêu cầu kết nối từ "
                + request.getClientName() + " (" + request.getClientAddress() + ").");
        if (pendingJoins.remove(request)) {
            notifyPendingJoins();
        }
        return true;
    }

    private void notifyPendingJoins() {
        Consumer<List<JoinRequest>> listener = this.joinRequestListener;
        try {
            listener.accept(getPendingJoins());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật danh sách yêu cầu kết nối: " + e.getMessage());
        }
    }

    /**
     * Khởi động server và trả về ngay sau khi socket đã lắng nghe thành công.
     * Việc chờ client được thực hiện trên luồng nền riêng.
     *
     * @param port cổng lắng nghe (1..65535)
     * @throws IOException nếu không thể mở cổng (ví dụ cổng đang bị chiếm)
     * @throws IllegalStateException nếu server đang chạy
     * @throws IllegalArgumentException nếu cổng không hợp lệ
     */
    public synchronized void start(int port) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng không hợp lệ: " + port + " (phải nằm trong khoảng 1..65535)");
        }
        if (running) {
            throw new IllegalStateException("Server đang chạy ở cổng " + this.port);
        }

        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }

        clientCounter.set(0);
        groupCounter.set(0);
        groups.clear();
        clientsByName.clear();
        this.serverSocket = socket;
        this.port = port;
        this.running = true;

        Thread thread = new Thread(this::acceptLoop, "server-accept");
        thread.setDaemon(true);
        this.acceptThread = thread;
        thread.start();

        log("Server đã khởi động, đang lắng nghe tại cổng " + port);
        notifyClientList();
        notifyGroupList();
    }

    /**
     * Dừng server: thông báo cho các client (best effort), đóng ServerSocket để
     * thoát vòng lặp accept và đóng toàn bộ kết nối đang hoạt động. An toàn khi
     * gọi nhiều lần.
     */
    public synchronized void stop() {
        if (!running) {
            log("Server đang không chạy, không cần dừng.");
            return;
        }

        int stoppedPort = this.port;
        running = false;

        // Cố gắng báo cho client biết lý do trước khi đóng kết nối.
        broadcastSystem("Server đang dừng, các kết nối sẽ bị đóng.");

        closeQuietly(serverSocket);
        serverSocket = null;

        // Đánh thức các client đang chờ duyệt để chúng không phải chờ hết thời hạn.
        for (JoinRequest request : pendingJoins) {
            request.decide(false);
        }
        pendingJoins.clear();
        notifyPendingJoins();

        for (ClientHandler handler : handlers) {
            handler.close();
        }
        handlers.clear();

        for (ClientHandler handler : pendingHandlers) {
            // Client đang chờ duyệt: đóng socket để không giữ kết nối treo.
            handler.close();
        }
        pendingHandlers.clear();
        clientsByName.clear();
        groups.clear();
        notifyClientCount();
        notifyGroupCount();
        notifyClientList();
        notifyGroupList();

        Thread thread = this.acceptThread;
        this.acceptThread = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        this.port = 0;
        log("Server đã dừng (cổng " + stoppedPort + ").");
    }

    /**
     * Vòng lặp chờ client kết nối. Chạy trên luồng nền "server-accept".
     */
    private void acceptLoop() {
        ServerSocket socket = this.serverSocket;
        if (socket == null) {
            return;
        }

        while (running) {
            try {
                Socket clientSocket = socket.accept();
                String clientAddress = describe(clientSocket);
                String clientName = "Client-" + clientCounter.incrementAndGet();
                log("Client " + clientName + " kết nối: " + clientAddress);

                ClientHandler handler = new ClientHandler(clientSocket, this, logger, clientName);
                Thread worker = new Thread(handler, "client-" + clientName);
                worker.setDaemon(true);
                worker.start();

                if (!running) {
                    // Server vừa được dừng trong lúc accept() trả về: đóng kết nối mới.
                    handler.close();
                    break;
                }
            } catch (SocketException e) {
                // ServerSocket bị đóng bởi stop() -> thoát vòng lặp bình thường.
                if (running) {
                    log("Lỗi ServerSocket: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    log("Lỗi khi chờ client kết nối: " + e.getMessage());
                }
            }
        }

        log("Đã kết thúc vòng lặp chờ kết nối.");
    }

    /**
     * Được {@link ClientHandler} gọi sau khi đã mở luồng dữ liệu và gửi tên định danh.
     *
     * @return {@code false} nếu server đã dừng trong lúc client đang kết nối
     */
    boolean registerHandler(ClientHandler handler) {
        if (!running) {
            return false;
        }
        handlers.add(handler);
        clientsByName.put(handler.getClientName(), handler);
        log(handler.getClientName() + " đã tham gia phòng chat. Số client đang kết nối: " + handlers.size());
        notifyClientCount();
        notifyClientList();
        broadcastSystem(handler.getClientName() + " đã tham gia phòng chat.");
        broadcastUserList();
        sendGroupList(handler);
        return true;
    }

    /**
     * Nhận yêu cầu tham gia của một client và chờ người quản trị quyết định. Được
     * {@link ClientHandler} gọi <b>trước khi</b> client được cấp tên, đăng ký vào
     * phòng hay nhận bất kỳ thông báo nào.
     *
     * @param handler handler của client đang chờ duyệt
     * @return {@code true} nếu được vào phòng chat
     */
    boolean requestJoin(ClientHandler handler) {
        if (!running) {
            return false;
        }

        if (autoApproveJoins) {
            log("Tự động chấp nhận yêu cầu kết nối từ " + handler.getClientName()
                    + " (" + handler.getClientAddress() + ").");
            return true;
        }

        JoinRequest request = new JoinRequest(handler.getClientName(), handler.getClientAddress());
        pendingHandlers.add(handler);
        pendingJoins.add(request);
        notifyPendingJoins();
        try {
            if (!request.awaitDecision(JOIN_DECISION_TIMEOUT_MS)) {
                log("Hết thời gian chờ duyệt yêu cầu kết nối từ " + handler.getClientName()
                        + " (" + handler.getClientAddress() + ").");
                return false;
            }
            return request.isApproved();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            pendingHandlers.remove(handler);
            if (pendingJoins.remove(request)) {
                notifyPendingJoins();
            }
        }
    }

    /**
     * Được {@link ClientHandler} gọi khi client ngắt kết nối hoặc gặp lỗi.
     *
     * <p>Client bị xóa khỏi mọi nhóm; các thành viên còn lại nhận {@code GLEFT}.
     * Nhóm rỗng bị xóa khỏi server.</p>
     */
    void removeHandler(ClientHandler handler) {
        if (!handlers.remove(handler)) {
            return;
        }
        String clientName = handler.getClientName();
        clientsByName.remove(clientName, handler);
        log(clientName + " đã rời phòng chat. Số client đang kết nối: " + handlers.size());
        notifyClientCount();
        notifyClientList();

        leaveAllGroups(clientName);
        cancelJoinRequests(clientName);

        if (running) {
            broadcastSystem(clientName + " đã rời phòng chat.");
            broadcastUserList();
        }
        broadcastGroupList();
        notifyGroupList();
    }

    /**
     * Bỏ mọi yêu cầu tham gia nhóm đang chờ của một client đã ngắt kết nối và cập nhật
     * danh sách yêu cầu cho trưởng nhóm của các nhóm liên quan.
     */
    private void cancelJoinRequests(String clientName) {
        List<String> ownersToNotify = new ArrayList<>();
        for (Group group : groups.values()) {
            if (group.removeJoinRequest(clientName) && !ownersToNotify.contains(group.getOwner())) {
                ownersToNotify.add(group.getOwner());
            }
        }
        for (String owner : ownersToNotify) {
            sendGroupRequests(owner);
        }
    }

    /**
     * Gửi một tin nhắn riêng từ {@code senderName} tới {@code targetName}.
     *
     * <p>Server chỉ gửi cho đúng hai client (người gửi nhận bản echo và người nhận),
     * không phát cho các client khác. Server ghi nhật ký nội dung theo yêu cầu
     * "server thấy tin nhắn".</p>
     *
     * @param senderName tên người gửi do server quản lý
     * @param targetName tên người nhận
     * @param text       nội dung tin nhắn
     */
    void sendPrivateMessage(String senderName, String targetName, String text) {
        String target = (targetName != null) ? targetName.trim() : "";
        if (target.isEmpty()) {
            sendError(senderName, Protocol.ERROR_BAD_REQUEST, "Thiếu tên người nhận.");
            return;
        }
        if (target.equals(senderName)) {
            sendError(senderName, Protocol.ERROR_SELF_TARGET, "Không thể gửi tin riêng cho chính mình.");
            return;
        }

        ClientHandler sender = clientsByName.get(senderName);
        ClientHandler receiver = clientsByName.get(target);
        if (sender == null) {
            return;
        }
        if (receiver == null) {
            sendError(senderName, Protocol.ERROR_TARGET_NOT_FOUND,
                    "Không tìm thấy người nhận " + target + " (có thể đã ngắt kết nối).");
            return;
        }

        String line = Protocol.serverPrivateMessage(senderName, target, text);
        synchronized (broadcastLock) {
            sender.sendLine(line);
            receiver.sendLine(line);
        }
        log("[Riêng] " + senderName + " -> " + target + ": " + text);
    }

    /**
     * Tạo nhóm mới với người tạo và danh sách thành viên được mời.
     *
     * <p>Chỉ kiểm tra số thành viên tối thiểu ({@link Protocol#MIN_GROUP_MEMBERS})
     * tại đây. Sau khi tạo, nhóm vẫn hoạt động khi số thành viên giảm xuống 1-2.</p>
     *
     * @param creatorName tên người tạo nhóm
     * @param invited     tên các thành viên được mời (không cần gồm người tạo)
     */
    void createGroup(String creatorName, List<String> invited) {
        List<String> memberNames = new ArrayList<>();
        memberNames.add(creatorName);
        if (invited != null) {
            for (String name : invited) {
                if (name == null) {
                    continue;
                }
                String trimmed = name.trim();
                if (!trimmed.isEmpty() && !memberNames.contains(trimmed)) {
                    memberNames.add(trimmed);
                }
            }
        }

        synchronized (broadcastLock) {
            if (!running || !clientsByName.containsKey(creatorName)) {
                return;
            }
            for (String name : memberNames) {
                if (!clientsByName.containsKey(name)) {
                    sendError(creatorName, Protocol.ERROR_UNKNOWN_MEMBER,
                            "Thành viên " + name + " không online.");
                    return;
                }
            }
            if (memberNames.size() < Protocol.MIN_GROUP_MEMBERS) {
                sendError(creatorName, Protocol.ERROR_GROUP_TOO_SMALL,
                        "Nhóm cần ít nhất " + Protocol.MIN_GROUP_MEMBERS
                                + " thành viên (hiện có " + memberNames.size() + ").");
                return;
            }
            if (memberNames.size() > MAX_GROUP_MEMBERS) {
                sendError(creatorName, Protocol.ERROR_GROUP_TOO_LARGE,
                        "Nhóm tối đa " + MAX_GROUP_MEMBERS + " thành viên.");
                return;
            }
            if (countGroupsOf(creatorName) >= MAX_GROUPS_PER_CLIENT) {
                sendError(creatorName, Protocol.ERROR_TOO_MANY_GROUPS,
                        "Bạn đã tham gia tối đa " + MAX_GROUPS_PER_CLIENT + " nhóm.");
                return;
            }

            String groupId = "G" + groupCounter.incrementAndGet();
            Group group = new Group(groupId, creatorName, "Nhóm của " + creatorName, memberNames);
            groups.put(groupId, group);

            String line = Protocol.groupInfoMessage(groupId, memberNames);
            for (String name : memberNames) {
                sendLineTo(name, line);
            }
            log("Tạo nhóm " + groupId + " (trưởng nhóm " + creatorName + "): "
                    + String.join(", ", memberNames));
        }
        broadcastGroupList();
        notifyGroupCount();
        notifyGroupList();
    }
    /**
     * Xử lý yêu cầu tham gia nhóm của một client.
     *
     * <p>Yêu cầu không được thêm thành viên ngay mà được ghi vào danh sách "chờ duyệt"
     * của nhóm; trưởng nhóm (người tạo nhóm) nhận được danh sách yêu cầu qua các dòng
     * {@code GJREQ} và quyết định duyệt/từ chối bằng {@link #resolveGroupJoin}.</p>
     *
     * @param requesterName tên client gửi yêu cầu
     * @param groupId       mã nhóm muốn tham gia
     */
    void requestJoinGroup(String requesterName, String groupId) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            sendError(requesterName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(requesterName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!clientsByName.containsKey(requesterName)) {
            return;
        }

        String ownerName;
        synchronized (broadcastLock) {
            if (group.contains(requesterName)) {
                sendError(requesterName, Protocol.ERROR_ALREADY_MEMBER,
                        "Bạn đã là thành viên nhóm " + labelOf(group) + ".");
                return;
            }
            if (group.size() >= MAX_GROUP_MEMBERS) {
                sendError(requesterName, Protocol.ERROR_GROUP_TOO_LARGE,
                        "Nhóm " + labelOf(group) + " đã đủ " + MAX_GROUP_MEMBERS + " thành viên.");
                return;
            }
            if (countGroupsOf(requesterName) >= MAX_GROUPS_PER_CLIENT) {
                sendError(requesterName, Protocol.ERROR_TOO_MANY_GROUPS,
                        "Bạn đã tham gia tối đa " + MAX_GROUPS_PER_CLIENT + " nhóm.");
                return;
            }
            if (!group.addJoinRequest(requesterName)) {
                sendError(requesterName, Protocol.ERROR_JOIN_PENDING,
                        "Bạn đã gửi yêu cầu tham gia nhóm " + labelOf(group)
                                + " và đang chờ trưởng nhóm duyệt.");
                return;
            }
            ownerName = group.getOwner();
            sendLineTo(requesterName, Protocol.systemMessage("Đã gửi yêu cầu tham gia nhóm "
                    + labelOf(group) + " tới trưởng nhóm " + ownerName + ". Vui lòng chờ duyệt."));
            sendLineTo(ownerName, Protocol.systemMessage(requesterName
                    + " xin tham gia nhóm " + labelOf(group)
                    + ". Hãy duyệt hoặc từ chối trong tab Nhóm."));
            sendGroupRequests(ownerName);
        }
        log(requesterName + " xin tham gia nhóm " + id + " (chờ " + ownerName + " duyệt).");
    }

    /**
     * Trưởng nhóm duyệt hoặc từ chối một yêu cầu tham gia nhóm.
     *
     * @param ownerName     tên client đang giữ quyền trưởng nhóm
     * @param groupId       mã nhóm
     * @param requesterName tên client đã gửi yêu cầu
     * @param accept        {@code true} để duyệt, {@code false} để từ chối
     */
    void resolveGroupJoin(String ownerName, String groupId, String requesterName, boolean accept) {
        String id = (groupId != null) ? groupId.trim() : "";
        String requester = (requesterName != null) ? requesterName.trim() : "";
        if (id.isEmpty() || requester.isEmpty()) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm hoặc tên client cần duyệt.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(ownerName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.isOwner(ownerName)) {
            sendError(ownerName, Protocol.ERROR_NOT_OWNER,
                    "Bạn không phải trưởng nhóm " + labelOf(group) + " nên không thể duyệt yêu cầu.");
            return;
        }

        synchronized (broadcastLock) {
            if (!group.hasJoinRequest(requester)) {
                sendError(ownerName, Protocol.ERROR_NO_JOIN_REQUEST,
                        "Không có yêu cầu tham gia nhóm " + labelOf(group)
                                + " của " + requester + " (có thể đã được xử lý).");
                return;
            }
            group.removeJoinRequest(requester);

            if (accept) {
                String failure = addMemberInternal(group, requester);
                if (failure != null) {
                    sendError(ownerName, Protocol.ERROR_UNKNOWN_MEMBER, failure);
                    sendLineTo(requester, Protocol.groupJoinResultMessage(id, false, failure));
                    log("Duyệt " + requester + " vào nhóm " + id + " thất bại: " + failure);
                } else {
                    sendLineTo(requester, Protocol.groupJoinResultMessage(id, true,
                            "Trưởng nhóm " + ownerName + " đã duyệt bạn vào nhóm "
                                    + labelOf(group) + "."));
                    log("Trưởng nhóm " + ownerName + " duyệt " + requester + " vào nhóm " + id + ".");
                }
            } else {
                sendLineTo(requester, Protocol.groupJoinResultMessage(id, false,
                        "Trưởng nhóm " + ownerName + " đã từ chối yêu cầu tham gia nhóm "
                                + labelOf(group) + "."));
                log("Trưởng nhóm " + ownerName + " từ chối " + requester + " vào nhóm " + id + ".");
            }

            sendGroupRequests(ownerName);
            broadcastGroupList();
        }
        notifyGroupCount();
        notifyGroupList();
    }

    /**
     * Trưởng nhóm thêm trực tiếp một client đang online vào nhóm (không cần chờ duyệt).
     *
     * @param ownerName  tên client đang giữ quyền trưởng nhóm
     * @param groupId    mã nhóm
     * @param memberName tên client cần thêm
     */
    void addMemberByOwner(String ownerName, String groupId, String memberName) {
        String id = (groupId != null) ? groupId.trim() : "";
        String member = (memberName != null) ? memberName.trim() : "";
        if (id.isEmpty() || member.isEmpty()) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm hoặc tên thành viên.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(ownerName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.isOwner(ownerName)) {
            sendError(ownerName, Protocol.ERROR_NOT_OWNER,
                    "Bạn không phải trưởng nhóm " + labelOf(group) + " nên không thể thêm thành viên.");
            return;
        }
        if (!clientsByName.containsKey(member)) {
            sendError(ownerName, Protocol.ERROR_UNKNOWN_MEMBER,
                    "Không tìm thấy " + member + " (có thể đã ngắt kết nối).");
            return;
        }

        synchronized (broadcastLock) {
            String failure = addMemberInternal(group, member);
            if (failure != null) {
                sendError(ownerName, Protocol.ERROR_BAD_REQUEST, failure);
                return;
            }
            sendGroupRequests(ownerName);
            broadcastGroupList();
        }
        log("Trưởng nhóm " + ownerName + " thêm " + member + " vào nhóm " + id + ".");
        notifyGroupCount();
        notifyGroupList();
    }

    /**
     * Trưởng nhóm xóa một thành viên khỏi nhóm.
     *
     * @param ownerName  tên client đang giữ quyền trưởng nhóm
     * @param groupId    mã nhóm
     * @param memberName tên thành viên cần xóa
     */
    void removeMemberByOwner(String ownerName, String groupId, String memberName) {
        String id = (groupId != null) ? groupId.trim() : "";
        String member = (memberName != null) ? memberName.trim() : "";
        if (id.isEmpty() || member.isEmpty()) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm hoặc tên thành viên.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(ownerName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.isOwner(ownerName)) {
            sendError(ownerName, Protocol.ERROR_NOT_OWNER,
                    "Bạn không phải trưởng nhóm " + labelOf(group) + " nên không thể xóa thành viên.");
            return;
        }
        if (member.equals(ownerName)) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST,
                    "Bạn không thể tự xóa mình khỏi nhóm; hãy dùng nút \"Rời nhóm\" hoặc \"Xóa nhóm\".");
            return;
        }

        synchronized (broadcastLock) {
            if (!group.contains(member)) {
                sendError(ownerName, Protocol.ERROR_NOT_MEMBER,
                        member + " không phải thành viên nhóm " + labelOf(group) + ".");
                return;
            }
            removeMemberAndNotify(group, member, "bị trưởng nhóm " + ownerName + " xóa khỏi nhóm");
            sendLineTo(member, Protocol.groupDeletedMessage(id,
                    "Bạn đã bị trưởng nhóm " + ownerName + " xóa khỏi nhóm " + labelOf(group) + "."));
            sendGroupRequests(ownerName);
            broadcastGroupList();
        }
        log("Trưởng nhóm " + ownerName + " xóa " + member + " khỏi nhóm " + id + ".");
        notifyGroupCount();
        notifyGroupList();
    }

    /**
     * Trưởng nhóm đổi tên nhóm.
     *
     * @param ownerName tên client đang giữ quyền trưởng nhóm
     * @param groupId   mã nhóm
     * @param newName   tên mới
     */
    void renameGroupByOwner(String ownerName, String groupId, String newName) {
        String id = (groupId != null) ? groupId.trim() : "";
        String name = (newName != null) ? newName.trim() : "";
        if (id.isEmpty() || name.isEmpty()) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm hoặc tên nhóm mới.");
            return;
        }
        if (name.length() > Group.MAX_NAME_LENGTH) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST,
                    "Tên nhóm tối đa " + Group.MAX_NAME_LENGTH + " ký tự.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(ownerName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.isOwner(ownerName)) {
            sendError(ownerName, Protocol.ERROR_NOT_OWNER,
                    "Bạn không phải trưởng nhóm " + labelOf(group) + " nên không thể đổi tên nhóm.");
            return;
        }

        String oldName = group.getName();
        group.setName(name);
        synchronized (broadcastLock) {
            sendToNames(group.snapshotMembers(), Protocol.systemMessage(ownerName
                    + " đã đổi tên nhóm " + id + " từ \"" + oldName + "\" thành \"" + name + "\"."));
            broadcastGroupList();
        }
        log("Trưởng nhóm " + ownerName + " đổi tên nhóm " + id + ": \"" + oldName
                + "\" -> \"" + name + "\".");
        notifyGroupList();
    }

    /**
     * Trưởng nhóm xóa hẳn nhóm; mọi thành viên nhận dòng {@code GDEL} để gỡ nhóm khỏi giao diện.
     *
     * @param ownerName tên client đang giữ quyền trưởng nhóm
     * @param groupId   mã nhóm
     */
    void deleteGroupByOwner(String ownerName, String groupId) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            sendError(ownerName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(ownerName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.isOwner(ownerName)) {
            sendError(ownerName, Protocol.ERROR_NOT_OWNER,
                    "Bạn không phải trưởng nhóm " + labelOf(group) + " nên không thể xóa nhóm.");
            return;
        }

        synchronized (broadcastLock) {
            List<String> members = group.snapshotMembers();
            if (!groups.remove(id, group)) {
                sendError(ownerName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
                return;
            }
            sendToNames(members, Protocol.groupDeletedMessage(id,
                    "Trưởng nhóm " + ownerName + " đã xóa nhóm " + labelOf(group) + "."));
            sendGroupRequests(ownerName);
            broadcastGroupList();
        }
        log("Trưởng nhóm " + ownerName + " xóa nhóm " + id + ".");
        notifyGroupCount();
        notifyGroupList();
    }

    /**
     * Thêm một client vào nhóm và thông báo cho các thành viên.
     *
     * <p>Phương thức này giả định người gọi đã giữ {@code broadcastLock} để thứ tự các
     * dòng gửi xuống client luôn nhất quán.</p>
     *
     * @return {@code null} nếu thêm thành công, ngược lại là thông điệp lỗi tiếng Việt
     */
    private String addMemberInternal(Group group, String memberName) {
        ClientHandler handler = clientsByName.get(memberName);
        if (handler == null || handler.isClosed()) {
            return memberName + " không còn kết nối.";
        }
        if (group.size() >= MAX_GROUP_MEMBERS) {
            return "Nhóm " + labelOf(group) + " đã đủ " + MAX_GROUP_MEMBERS + " thành viên.";
        }
        if (countGroupsOf(memberName) >= MAX_GROUPS_PER_CLIENT) {
            return memberName + " đã tham gia tối đa " + MAX_GROUPS_PER_CLIENT + " nhóm.";
        }
        if (!group.addMember(memberName)) {
            return memberName + " đã là thành viên nhóm " + labelOf(group) + ".";
        }

        group.removeJoinRequest(memberName);
        sendLineTo(memberName, Protocol.groupInfoMessage(group.getId(), group.snapshotMembers()));
        List<String> others = new ArrayList<>(group.snapshotMembers());
        others.remove(memberName);
        sendToNames(others, Protocol.systemMessage(memberName
                + " đã tham gia nhóm " + labelOf(group) + "."));
        return null;
    }

    /**
     * Gửi ảnh chụp danh sách toàn bộ nhóm (các dòng {@code GALL} + {@code GALL_END}).
     *
     * @param handler client cần nhận danh sách (bỏ qua nếu {@code null})
     */
    void sendGroupList(ClientHandler handler) {
        if (handler == null) {
            return;
        }
        synchronized (broadcastLock) {
            for (String line : groupListLines()) {
                handler.sendLine(line);
            }
        }
    }

    /**
     * Phát ảnh chụp danh sách toàn bộ nhóm cho mọi client đang kết nối.
     * Client dùng danh sách này để hiển thị "Nhóm của bạn" và "Tất cả nhóm".
     */
    private void broadcastGroupList() {
        synchronized (broadcastLock) {
            List<String> lines = groupListLines();
            for (ClientHandler handler : handlers) {
                if (handler.isClosed()) {
                    continue;
                }
                for (String line : lines) {
                    handler.sendLine(line);
                }
            }
        }
    }

    /**
     * Gửi ảnh chụp danh sách yêu cầu chờ duyệt của các nhóm do một client làm trưởng nhóm.
     *
     * @param ownerName tên trưởng nhóm cần nhận danh sách
     */
    private void sendGroupRequests(String ownerName) {
        ClientHandler handler = clientsByName.get(ownerName);
        if (handler == null) {
            return;
        }
        synchronized (broadcastLock) {
            List<Group> snapshot = new ArrayList<>(groups.values());
            snapshot.sort(Comparator.comparingInt(group -> groupNumber(group.getId())));
            List<String> lines = new ArrayList<>();
            for (Group group : snapshot) {
                if (group.isOwner(ownerName) && group.joinRequestCount() > 0) {
                    lines.add(Protocol.groupRequestsMessage(group.getId(), group.snapshotJoinRequests()));
                }
            }
            lines.add(Protocol.groupRequestsEndMessage());
            for (String line : lines) {
                handler.sendLine(line);
            }
        }
    }

    /**
     * Tạo các dòng mô tả toàn bộ nhóm, kết thúc bằng {@code GALL_END}.
     * Người gọi phải giữ {@code broadcastLock}.
     */
    private List<String> groupListLines() {
        List<Group> snapshot = new ArrayList<>(groups.values());
        snapshot.sort(Comparator.comparingInt(group -> groupNumber(group.getId())));
        List<String> lines = new ArrayList<>(snapshot.size() + 1);
        for (Group group : snapshot) {
            lines.add(Protocol.groupListItemMessage(group.getId(), group.getOwner(),
                    group.snapshotMembers(), group.getName()));
        }
        lines.add(Protocol.groupListEndMessage());
        return lines;
    }

    /**
     * @param group nhóm cần lấy nhãn
     * @return nhãn hiển thị dạng {@code "Tên nhóm (G1)"} hoặc chỉ mã nhóm nếu chưa có tên
     */
    private String labelOf(Group group) {
        String name = group.getName();
        if (name == null || name.isBlank() || name.equals(group.getId())) {
            return group.getId();
        }
        return name + " (" + group.getId() + ")";
    }


    /**
     * Gửi tin nhắn của một thành viên tới tất cả thành viên còn lại của nhóm.
     *
     * @param senderName tên người gửi
     * @param groupId    mã nhóm
     * @param text       nội dung tin nhắn
     */
    void sendGroupMessage(String senderName, String groupId, String text) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            sendError(senderName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }

        Group group = groups.get(id);
        if (group == null) {
            sendError(senderName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.contains(senderName)) {
            sendError(senderName, Protocol.ERROR_NOT_MEMBER, "Bạn không phải thành viên nhóm " + id + ".");
            return;
        }

        String line = Protocol.serverGroupMessage(id, senderName, text);
        synchronized (broadcastLock) {
            for (String member : group.snapshotMembers()) {
                sendLineTo(member, line);
            }
        }
        log("[Nhóm " + id + "] " + senderName + ": " + text);
    }

    /**
     * Cho một client chủ động rời nhóm.
     *
     * @param senderName tên client
     * @param groupId    mã nhóm
     */
    void leaveGroup(String senderName, String groupId) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            sendError(senderName, Protocol.ERROR_BAD_REQUEST, "Thiếu mã nhóm.");
            return;
        }
        Group group = groups.get(id);
        if (group == null) {
            sendError(senderName, Protocol.ERROR_GROUP_NOT_FOUND, "Nhóm " + id + " không tồn tại.");
            return;
        }
        if (!group.contains(senderName)) {
            sendError(senderName, Protocol.ERROR_NOT_MEMBER, "Bạn không phải thành viên nhóm " + id + ".");
            return;
        }
        removeMemberAndNotify(group, senderName, "đã rời nhóm");
        broadcastGroupList();
        notifyGroupCount();
        notifyGroupList();
    }

    /**
     * Xóa client khỏi tất cả nhóm (dùng khi client mất kết nối).
     */
    private void leaveAllGroups(String clientName) {
        if (groups.isEmpty()) {
            return;
        }
        for (Group group : new ArrayList<>(groups.values())) {
            if (group.contains(clientName)) {
                removeMemberAndNotify(group, clientName, "mất kết nối");
            }
        }
        notifyGroupCount();
    }

    /**
     * Xóa một thành viên khỏi nhóm và thông báo cho các thành viên còn lại.
     * Nhóm rỗng bị xóa khỏi registry.
     *
     * <p>Nếu người bị xóa là trưởng nhóm, quyền trưởng nhóm được chuyển cho thành viên
     * đầu tiên còn lại để nhóm vẫn có người duyệt yêu cầu tham gia và quản lý thành viên.
     * Yêu cầu tham gia đang chờ của người bị xóa cũng bị bỏ.</p>
     */
    private void removeMemberAndNotify(Group group, String memberName, String reason) {
        if (!group.removeMember(memberName)) {
            return;
        }
        group.removeJoinRequest(memberName);
        if (group.isEmpty()) {
            groups.remove(group.getId(), group);
            log("Nhóm " + group.getId() + " không còn thành viên, đã xóa.");
            return;
        }
        if (group.isOwner(memberName)) {
            String newOwner = group.snapshotMembers().get(0);
            group.setOwner(newOwner);
            sendLineTo(newOwner, Protocol.systemMessage(
                    "Bạn trở thành trưởng nhóm mới của nhóm " + group.getId() + "."));
            sendGroupRequests(newOwner);
            log("Chuyển quyền trưởng nhóm " + group.getId() + " sang " + newOwner + ".");
        }
        String line = Protocol.groupMemberLeftMessage(group.getId(), memberName, reason);
        sendToNames(group.snapshotMembers(), line);
        log(memberName + " " + reason + " khỏi nhóm " + group.getId()
                + " (còn " + group.size() + " thành viên).");
    }

    /**
     * Gửi danh sách client đang online cho một client (trả lời yêu cầu {@code USERS}).
     *
     * @param handler client cần nhận danh sách
     */
    void sendUserList(ClientHandler handler) {
        synchronized (broadcastLock) {
            handler.sendLine(Protocol.userListMessage(onlineClientNames()));
        }
    }

    /**
     * Phát danh sách client đang online cho tất cả client.
     */
    private void broadcastUserList() {
        broadcastLine(Protocol.userListMessage(onlineClientNames()));
    }

    /**
     * @return tên các client đang kết nối, theo thứ tự tham gia
     */
    public List<String> getOnlineClientNames() {
        List<String> names = new ArrayList<>();
        for (ClientHandler handler : handlers) {
            if (!handler.isClosed()) {
                names.add(handler.getClientName());
            }
        }
        return names;
    }

    private List<String> onlineClientNames() {
        return getOnlineClientNames();
    }

    /**
     * Gửi một dòng tới danh sách client được chỉ định (dùng cho DM và tin nhóm).
     */
    private void sendToNames(Collection<String> names, String line) {
        if (names == null || line == null) {
            return;
        }
        synchronized (broadcastLock) {
            for (String name : names) {
                sendLineTo(name, line);
            }
        }
    }

    /**
     * Gửi một dòng tới đúng một client theo tên; bỏ qua nếu client đã rời.
     */
    private void sendLineTo(String name, String line) {
        ClientHandler handler = clientsByName.get(name);
        if (handler != null) {
            handler.sendLine(line);
        }
    }

    /**
     * Gửi thông báo lỗi tới một client (nếu còn kết nối).
     */
    private void sendError(String name, String code, String message) {
        ClientHandler handler = clientsByName.get(name);
        if (handler != null) {
            handler.sendLine(Protocol.errorMessage(code, message));
        }
    }

    private int countGroupsOf(String clientName) {
        int count = 0;
        for (Group group : groups.values()) {
            if (group.contains(clientName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Phát một tin nhắn công khai tới tất cả client đang kết nối (kể cả người gửi).
     *
     * @param senderName tên người gửi do server quản lý
     * @param text       nội dung tin nhắn
     */
    void broadcastPublicMessage(String senderName, String text) {
        broadcastLine(Protocol.serverChatMessage(senderName, text));
    }

    /**
     * Phát một thông báo hệ thống tới tất cả client đang kết nối.
     *
     * @param text nội dung thông báo
     */
    void broadcastSystem(String text) {
        broadcastLine(Protocol.systemMessage(text));
    }

    /**
     * Gửi một dòng tới mọi client. Khóa {@code broadcastLock} bảo đảm thứ tự tin
     * nhắn giống nhau ở mọi client; {@link ClientHandler#sendLine(String)} tự bỏ
     * qua client đã đóng nên một client lỗi không ảnh hưởng người khác.
     */
    private void broadcastLine(String line) {
        synchronized (broadcastLock) {
            int failed = 0;
            for (ClientHandler handler : handlers) {
                if (!handler.sendLine(line) && !handler.isClosed()) {
                    failed++;
                }
            }
            if (failed > 0) {
                log("Không gửi được tin nhắn tới " + failed + " client (kết nối đã đóng).");
            }
        }
    }

    private void notifyClientCount() {
        Consumer<Integer> listener = this.clientCountListener;
        try {
            listener.accept(handlers.size());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật số client: " + e.getMessage());
        }
    }

    private void notifyGroupCount() {
        Consumer<Integer> listener = this.groupCountListener;
        try {
            listener.accept(groups.size());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật số nhóm: " + e.getMessage());
        }
    }

    /**
     * Gửi danh sách client đang kết nối cho giao diện quản lý người dùng. Lỗi trong
     * listener của giao diện không được làm hỏng luồng server.
     */
    private void notifyClientList() {
        Consumer<List<ClientInfo>> listener = this.clientListListener;
        try {
            listener.accept(getConnectedClients());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật danh sách client: " + e.getMessage());
        }
    }

    /**
     * Gửi danh sách nhóm (kèm thành viên) cho giao diện quản lý nhóm.
     */
    private void notifyGroupList() {
        Consumer<List<GroupInfo>> listener = this.groupListListener;
        try {
            listener.accept(getGroups());
        } catch (RuntimeException e) {
            log("Lỗi khi cập nhật danh sách nhóm: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public int getClientCount() {
        return handlers.size();
    }

    /**
     * @return số nhóm hiện có trên server
     */
    public int getGroupCount() {
        return groups.size();
    }

    /**
     * @return danh sách client đang kết nối (đã được duyệt), theo thứ tự tham gia
     */
    public List<ClientInfo> getConnectedClients() {
        List<ClientInfo> result = new ArrayList<>();
        for (ClientHandler handler : handlers) {
            if (!handler.isClosed()) {
                result.add(new ClientInfo(handler.getClientName(), handler.getClientAddress()));
            }
        }
        return result;
    }

    /**
     * @return danh sách nhóm hiện có kèm thành viên, sắp xếp theo số hiệu nhóm
     */
    public List<GroupInfo> getGroups() {
        List<Group> snapshot = new ArrayList<>(groups.values());
        snapshot.sort(Comparator.comparingInt(group -> groupNumber(group.getId())));
        List<GroupInfo> result = new ArrayList<>(snapshot.size());
        for (Group group : snapshot) {
            result.add(new GroupInfo(group));
        }
        return result;
    }

    /**
     * Ngắt kết nối của một client theo yêu cầu của quản trị viên Server.
     *
     * <p>Client nhận thông báo {@code SYS} + {@code ERR|KICKED} trước khi socket bị đóng;
     * các client còn lại nhận thông báo rời phòng như khi client tự ngắt kết nối.</p>
     *
     * @param clientName tên client cần ngắt kết nối
     * @return {@code null} nếu thành công, ngược lại là thông điệp lỗi tiếng Việt cho giao diện
     */
    public String disconnectClient(String clientName) {
        String name = (clientName != null) ? clientName.trim() : "";
        if (name.isEmpty()) {
            return "Thiếu tên client cần ngắt kết nối.";
        }
        ClientHandler handler = clientsByName.get(name);
        if (handler == null || handler.isClosed()) {
            return "Client " + name + " không còn kết nối.";
        }

        log("Quản trị viên ngắt kết nối " + name + ".");
        synchronized (broadcastLock) {
            handler.sendLine(Protocol.systemMessage("Bạn đã bị quản trị viên ngắt kết nối."));
            handler.sendLine(Protocol.errorMessage(Protocol.ERROR_KICKED,
                    "Quản trị viên đã ngắt kết nối của bạn."));
            handler.close();
        }
        return null;
    }

    /**
     * Quản trị viên thêm một client đang kết nối vào nhóm.
     *
     * <p>Client được thêm nhận {@code GROUP} để tự hiện nhóm; các thành viên cũ nhận
     * thông báo {@code SYS}. Các giới hạn giống lúc tạo nhóm được kiểm tra lại ở Server.</p>
     *
     * @param groupId    mã nhóm
     * @param memberName tên client cần thêm
     * @return {@code null} nếu thành công, ngược lại là thông điệp lỗi tiếng Việt
     */
    public String addGroupMember(String groupId, String memberName) {
        String id = (groupId != null) ? groupId.trim() : "";
        String member = (memberName != null) ? memberName.trim() : "";
        if (id.isEmpty()) {
            return "Thiếu mã nhóm.";
        }
        if (member.isEmpty()) {
            return "Thiếu tên client cần thêm.";
        }
        Group group = groups.get(id);
        if (group == null) {
            return "Nhóm " + id + " không tồn tại.";
        }
        if (group.contains(member)) {
            return member + " đã là thành viên của nhóm " + id + ".";
        }
        ClientHandler memberHandler = clientsByName.get(member);
        if (memberHandler == null || memberHandler.isClosed()) {
            return "Client " + member + " không còn kết nối.";
        }
        if (group.size() >= MAX_GROUP_MEMBERS) {
            return "Nhóm " + id + " đã đủ " + MAX_GROUP_MEMBERS + " thành viên.";
        }
        if (countGroupsOf(member) >= MAX_GROUPS_PER_CLIENT) {
            return member + " đã tham gia tối đa " + MAX_GROUPS_PER_CLIENT + " nhóm.";
        }

        synchronized (broadcastLock) {
            if (!group.addMember(member)) {
                return member + " đã là thành viên của nhóm " + id + ".";
            }
            group.removeJoinRequest(member);
            sendLineTo(member, Protocol.groupInfoMessage(id, group.snapshotMembers()));
            List<String> others = new ArrayList<>(group.snapshotMembers());
            others.remove(member);
            sendToNames(others, Protocol.systemMessage(
                    member + " đã được quản trị viên thêm vào nhóm " + id + "."));
            sendGroupRequests(group.getOwner());
            broadcastGroupList();
        }
        log("Quản trị viên thêm " + member + " vào nhóm " + id
                + " (còn " + group.size() + " thành viên).");
        notifyGroupCount();
        notifyGroupList();
        return null;
    }

    /**
     * Quản trị viên xóa một thành viên khỏi nhóm.
     *
     * <p>Thành viên bị xóa nhận {@code GDEL} nên client loại nhóm khỏi danh sách; các
     * thành viên còn lại nhận {@code GLEFT}. Nhóm rỗng bị xóa khỏi Server.</p>
     *
     * @param groupId    mã nhóm
     * @param memberName tên thành viên cần xóa
     * @return {@code null} nếu thành công, ngược lại là thông điệp lỗi tiếng Việt
     */
    public String removeGroupMember(String groupId, String memberName) {
        String id = (groupId != null) ? groupId.trim() : "";
        String member = (memberName != null) ? memberName.trim() : "";
        if (id.isEmpty()) {
            return "Thiếu mã nhóm.";
        }
        if (member.isEmpty()) {
            return "Thiếu tên thành viên cần xóa.";
        }
        Group group = groups.get(id);
        if (group == null) {
            return "Nhóm " + id + " không tồn tại.";
        }
        if (!group.contains(member)) {
            return member + " không phải thành viên của nhóm " + id + ".";
        }

        synchronized (broadcastLock) {
            removeMemberAndNotify(group, member, "bị quản trị viên xóa khỏi nhóm");
            sendLineTo(member, Protocol.groupDeletedMessage(id,
                    "Bạn đã bị quản trị viên xóa khỏi nhóm " + id + "."));
            broadcastGroupList();
        }
        log("Quản trị viên xóa " + member + " khỏi nhóm " + id + ".");
        notifyGroupCount();
        notifyGroupList();
        return null;
    }

    /**
     * Đổi tên hiển thị của nhóm (chỉ phía Server quản lý; các client nhận thông báo
     * {@code SYS}).
     *
     * @param groupId mã nhóm
     * @param newName tên mới
     * @return {@code null} nếu thành công, ngược lại là thông điệp lỗi tiếng Việt
     */
    public String renameGroup(String groupId, String newName) {
        String id = (groupId != null) ? groupId.trim() : "";
        String name = (newName != null) ? newName.trim() : "";
        if (id.isEmpty()) {
            return "Thiếu mã nhóm.";
        }
        Group group = groups.get(id);
        if (group == null) {
            return "Nhóm " + id + " không tồn tại.";
        }
        if (name.isEmpty()) {
            return "Tên nhóm không được để trống.";
        }
        if (name.length() > Group.MAX_NAME_LENGTH) {
            return "Tên nhóm tối đa " + Group.MAX_NAME_LENGTH + " ký tự.";
        }

        String oldName = group.getName();
        group.setName(name);
        sendToNames(group.snapshotMembers(), Protocol.systemMessage(
                "Quản trị viên đã đổi tên nhóm " + id + " thành \"" + name + "\"."));
        broadcastGroupList();
        log("Quản trị viên đổi tên nhóm " + id + ": \"" + oldName + "\" -> \"" + name + "\".");
        notifyGroupList();
        return null;
    }

    /**
     * Xóa hẳn một nhóm; mọi thành viên nhận {@code GDEL} và client loại nhóm khỏi danh sách.
     *
     * @param groupId mã nhóm
     * @return {@code null} nếu thành công, ngược lại là thông điệp lỗi tiếng Việt
     */
    public String deleteGroup(String groupId) {
        String id = (groupId != null) ? groupId.trim() : "";
        if (id.isEmpty()) {
            return "Thiếu mã nhóm.";
        }
        Group group = groups.get(id);
        if (group == null) {
            return "Nhóm " + id + " không tồn tại.";
        }

        synchronized (broadcastLock) {
            groups.remove(id, group);
            sendToNames(group.snapshotMembers(), Protocol.groupDeletedMessage(id,
                    "Nhóm " + id + " đã bị quản trị viên xóa."));
            broadcastGroupList();
        }
        log("Quản trị viên xóa nhóm " + id + " (\"" + group.getName() + "\", "
                + group.size() + " thành viên).");
        notifyGroupCount();
        notifyGroupList();
        return null;
    }

    /**
     * Lấy số hiệu của mã nhóm để sắp xếp ({@code G2} trước {@code G10}).
     */
    private static int groupNumber(String groupId) {
        if (groupId == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(groupId.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Mô tả địa chỉ client dạng "ip:port" để ghi nhật ký.
     */
    static String describe(Socket socket) {
        if (socket == null) {
            return "unknown";
        }
        return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    private void log(String message) {
        logger.accept(message);
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Không còn gì để xử lý khi đóng socket.
        }
    }
}
