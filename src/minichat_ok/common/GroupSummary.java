package minichat_ok.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Ảnh chụp thông tin một nhóm được Server gửi xuống Client (giai đoạn 3, bổ sung).
 *
 * <p>Server gửi danh sách nhóm dưới dạng nhiều dòng {@code GALL} liên tiếp, mỗi dòng
 * một nhóm, kết thúc bằng {@code GALL_END}. Client ghép các dòng đó thành danh sách
 * {@code GroupSummary} để hiển thị <b>nhóm của mình</b> và <b>tất cả nhóm trong hệ
 * thống</b> (để gửi yêu cầu tham gia).</p>
 *
 * <p>Đối tượng là bất biến: danh sách thành viên đã được sao chép tại thời điểm tạo
 * nên giao diện đọc dữ liệu mà không cần khóa.</p>
 */
public final class GroupSummary {

    private final String id;
    private final String owner;
    private final String name;
    private final List<String> members;

    /**
     * @param id      mã nhóm, ví dụ {@code "G1"}
     * @param owner   tên trưởng nhóm (người tạo nhóm)
     * @param name    tên hiển thị của nhóm
     * @param members danh sách thành viên hiện tại
     */
    public GroupSummary(String id, String owner, String name, Collection<String> members) {
        this.id = (id != null) ? id.trim() : "";
        this.owner = (owner != null) ? owner.trim() : "";
        this.name = (name != null) ? name : "";
        this.members = (members == null) ? List.of() : List.copyOf(new ArrayList<>(members));
    }

    /** @return mã nhóm, ví dụ {@code "G1"} */
    public String getId() {
        return id;
    }

    /** @return tên trưởng nhóm (người tạo nhóm) */
    public String getOwner() {
        return owner;
    }

    /** @return tên hiển thị của nhóm (trưởng nhóm có thể đổi) */
    public String getName() {
        return name;
    }

    /** @return danh sách thành viên (chỉ đọc) */
    public List<String> getMembers() {
        return members;
    }

    /** @return số thành viên hiện tại */
    public int getMemberCount() {
        return members.size();
    }

    /**
     * @param clientName tên client cần kiểm tra (có thể {@code null})
     * @return {@code true} nếu client đang là thành viên của nhóm
     */
    public boolean contains(String clientName) {
        return clientName != null && members.contains(clientName);
    }

    /**
     * @param clientName tên client cần kiểm tra (có thể {@code null})
     * @return {@code true} nếu client là trưởng nhóm
     */
    public boolean isOwnedBy(String clientName) {
        return clientName != null && owner.equals(clientName);
    }

    /**
     * @return nhãn hiển thị dùng cho danh sách "Tất cả nhóm"
     */
    @Override
    public String toString() {
        String label = (name == null || name.isBlank() || name.equals(id)) ? id : name + " [" + id + "]";
        return label + " - trưởng nhóm: " + owner + " - " + members.size() + " thành viên";
    }
}
