package minichat_ok.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import minichat_ok.common.Protocol;

/**
 * Một nhóm chat phía Server (giai đoạn 3).
 *
 * <p>Nhóm được xác định bằng {@code id} (ví dụ {@code "G1"}) và giữ tập tên thành viên.
 * Tập này dùng {@link CopyOnWriteArraySet} để một luồng đang phát tin nhắn vẫn có thể
 * duyệt an toàn trong khi luồng khác thêm/xóa thành viên.</p>
 *
 * <p>Quy tắc nghiệp vụ: chỉ kiểm tra số thành viên tối thiểu
 * ({@link minichat_ok.common.Protocol#MIN_GROUP_MEMBERS}) <b>khi tạo nhóm</b>. Sau khi
 * tạo, nhóm vẫn hoạt động dù chỉ còn 1 hoặc 2 thành viên; nhóm chỉ bị xóa khi không
 * còn thành viên nào.</p>
 *
 * <p>Ngoài tập thành viên, nhóm còn có {@code name} — tên hiển thị do trưởng nhóm hoặc
 * quản trị viên Server đặt. Tên nhóm và trưởng nhóm được gửi xuống client trong dòng
 * {@code GALL} nên giao diện client hiển thị được.</p>
 *
 * <p>Từ giai đoạn 3 (bổ sung), nhóm còn giữ tập {@code joinRequests}: tên các client đã
 * gửi yêu cầu tham gia và đang chờ <b>trưởng nhóm</b> duyệt. Chỉ trưởng nhóm hiện tại
 * (người tạo nhóm, hoặc người được chuyển quyền khi trưởng nhóm rời nhóm) mới được
 * duyệt/từ chối, thêm/xóa thành viên, đổi tên và xóa nhóm.</p>
 */
public final class Group {

    /** Độ dài tối đa của tên nhóm (do trưởng nhóm/quản trị viên Server đặt). */
    public static final int MAX_NAME_LENGTH = Protocol.MAX_GROUP_NAME_LENGTH;

    private final String id;
    private volatile String owner;
    private volatile String name;
    private final Set<String> members;
    private final Set<String> joinRequests = new CopyOnWriteArraySet<>();

    /**
     * @param id           mã nhóm do Server sinh, ví dụ {@code "G1"}
     * @param owner        tên người tạo nhóm (trưởng nhóm, có quyền quản lý nhóm)
     * @param name         tên hiển thị của nhóm (trưởng nhóm có thể đổi)
     * @param memberNames  danh sách thành viên ban đầu, gồm cả người tạo
     */
    public Group(String id, String owner, String name, Collection<String> memberNames) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.members = new CopyOnWriteArraySet<>(memberNames);
    }

    public String getId() {
        return id;
    }

    /** @return tên trưởng nhóm hiện tại (người tạo nhóm hoặc người được chuyển quyền) */
    public String getOwner() {
        return owner;
    }

    /**
     * Chuyển quyền trưởng nhóm cho một thành viên khác (dùng khi trưởng nhóm rời nhóm).
     *
     * @param newOwner tên thành viên sẽ trở thành trưởng nhóm
     */
    public void setOwner(String newOwner) {
        this.owner = newOwner;
    }

    /**
     * @param clientName tên client cần kiểm tra (có thể {@code null})
     * @return {@code true} nếu client là trưởng nhóm của nhóm này
     */
    public boolean isOwner(String clientName) {
        return clientName != null && clientName.equals(owner);
    }

    /** @return tên hiển thị của nhóm (không nhất thiết duy nhất) */
    public String getName() {
        return name;
    }

    /**
     * Đặt tên mới cho nhóm.
     *
     * @param newName tên mới (đã được lớp gọi kiểm tra khác rỗng và độ dài)
     */
    public void setName(String newName) {
        this.name = newName;
    }

    /**
     * @return {@code true} nếu {@code name} đang là thành viên của nhóm
     */
    public boolean contains(String name) {
        return name != null && members.contains(name);
    }

    /**
     * Thêm thành viên (dùng khi khởi tạo hoặc mở rộng nhóm sau này).
     *
     * @param name tên thành viên
     * @return {@code true} nếu tập thành viên thay đổi
     */
    public boolean addMember(String name) {
        return name != null && members.add(name);
    }

    /**
     * Xóa một thành viên khỏi nhóm.
     *
     * @param name tên thành viên
     * @return {@code true} nếu thành viên đã được xóa
     */
    public boolean removeMember(String name) {
        return name != null && members.remove(name);
    }

    public int size() {
        return members.size();
    }

    /**
     * Ghi nhận một yêu cầu tham gia nhóm đang chờ trưởng nhóm duyệt.
     *
     * @param name tên client gửi yêu cầu
     * @return {@code true} nếu yêu cầu được thêm mới (chưa có yêu cầu trùng)
     */
    public boolean addJoinRequest(String name) {
        return name != null && joinRequests.add(name);
    }

    /**
     * Bỏ một yêu cầu tham gia nhóm (đã duyệt, bị từ chối hoặc người gửi đã offline).
     *
     * @param name tên client gửi yêu cầu
     * @return {@code true} nếu có yêu cầu được bỏ
     */
    public boolean removeJoinRequest(String name) {
        return name != null && joinRequests.remove(name);
    }

    /**
     * @param name tên client cần kiểm tra
     * @return {@code true} nếu client đang có yêu cầu tham gia chờ duyệt
     */
    public boolean hasJoinRequest(String name) {
        return name != null && joinRequests.contains(name);
    }

    /**
     * @return bản sao danh sách client đang xin vào nhóm tại thời điểm gọi
     */
    public List<String> snapshotJoinRequests() {
        return new ArrayList<>(joinRequests);
    }

    /**
     * @return số yêu cầu tham gia đang chờ duyệt
     */
    public int joinRequestCount() {
        return joinRequests.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * @return bản sao danh sách thành viên tại thời điểm gọi (an toàn khi đang phát tin)
     */
    public List<String> snapshotMembers() {
        return new ArrayList<>(members);
    }
}
