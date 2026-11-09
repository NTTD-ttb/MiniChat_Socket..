package minichat_ok.client;

import java.util.List;
import java.util.Map;

import minichat_ok.common.GroupSummary;

/**
 * Nơi nhận sự kiện từ {@link ChatClient} (giai đoạn 3).
 *
 * <p>Tất cả phương thức đều có cài đặt mặc định rỗng nên lớp giao diện chỉ cần
 * ghi đè những sự kiện mình quan tâm. Các phương thức được gọi trên luồng nhận
 * dữ liệu của client ({@code client-receiver}); lớp giao diện phải tự chuyển cập
 * nhật Swing về EDT bằng {@code SwingUtilities.invokeLater(...)}.</p>
 */
public interface ChatClientListener {

    /**
     * Nhận tin nhắn phòng chung (kể cả bản echo của chính mình).
     *
     * @param sender tên người gửi
     * @param text   nội dung
     */
    default void onChatMessage(String sender, String text) {
        // Không quan tâm.
    }

    /**
     * Nhận thông báo hệ thống (tham gia/rời phòng, server dừng...).
     *
     * @param message nội dung thông báo
     */
    default void onSystemMessage(String message) {
        // Không quan tâm.
    }

    /**
     * Server đã cấp tên định danh cho client này.
     *
     * @param name tên do server cấp, ví dụ {@code "Client-1"}
     */
    default void onNameAssigned(String name) {
        // Không quan tâm.
    }

    /**
     * Kết nối bị mất ngoài ý muốn (server dừng hoặc đường truyền đóng).
     *
     * @param reason mô tả lý do
     */
    default void onConnectionLost(String reason) {
        // Không quan tâm.
    }

    /**
     * Danh sách client đang online vừa thay đổi.
     *
     * @param users tên các client đang kết nối (gồm cả chính mình)
     */
    default void onUserList(List<String> users) {
        // Không quan tâm.
    }

    /**
     * Nhận tin nhắn riêng 1-1 (người gửi và người nhận đều nhận được bản echo).
     *
     * @param sender tên người gửi
     * @param target tên người nhận
     * @param text   nội dung
     */
    default void onPrivateMessage(String sender, String target, String text) {
        // Không quan tâm.
    }

    /**
     * Client này vừa được thêm vào một nhóm (do chính mình tạo hoặc được mời).
     *
     * @param groupId mã nhóm, ví dụ {@code "G1"}
     * @param members danh sách thành viên, gồm cả chính mình
     */
    default void onGroupCreated(String groupId, List<String> members) {
        // Không quan tâm.
    }

    /**
     * Nhận tin nhắn trong nhóm.
     *
     * @param groupId mã nhóm
     * @param sender  tên người gửi
     * @param text    nội dung
     */
    default void onGroupMessage(String groupId, String sender, String text) {
        // Không quan tâm.
    }

    /**
     * Một thành viên đã rời nhóm (chủ động rời hoặc mất kết nối).
     *
     * @param groupId mã nhóm
     * @param name    tên thành viên đã rời
     * @param reason  lý do, ví dụ {@code "đã rời nhóm"} hoặc {@code "mất kết nối"}
     */
    default void onGroupMemberLeft(String groupId, String name, String reason) {
        // Không quan tâm.
    }

    /**
     * Nhóm đã bị xóa hoặc client này bị quản trị viên xóa khỏi nhóm.
     *
     * @param groupId mã nhóm
     * @param reason  lý do do server gửi, ví dụ {@code "Nhóm G1 đã bị quản trị viên xóa."}
     */
    default void onGroupDeleted(String groupId, String reason) {
        // Không quan tâm.
    }

    /**
     * Danh sách <b>toàn bộ nhóm</b> trong hệ thống vừa thay đổi (giai đoạn 3 bổ sung).
     *
     * <p>Server gửi ảnh chụp danh sách nhóm mỗi khi có nhóm được tạo/xóa, thành viên
     * thay đổi hoặc tên nhóm thay đổi. Nhờ vậy client hiển thị được cả nhóm mình chưa
     * tham gia để gửi yêu cầu tham gia.</p>
     *
     * @param groups danh sách nhóm kèm trưởng nhóm, tên nhóm và thành viên
     */
    default void onGroupList(List<GroupSummary> groups) {
        // Không quan tâm.
    }

    /**
     * Danh sách yêu cầu tham gia nhóm đang chờ <b>trưởng nhóm là client này</b> duyệt.
     *
     * @param requests mỗi khóa là mã nhóm, giá trị là tên các client đang xin vào nhóm
     */
    default void onGroupJoinRequests(Map<String, List<String>> requests) {
        // Không quan tâm.
    }

    /**
     * Trưởng nhóm đã duyệt/từ chối yêu cầu tham gia nhóm của client này.
     *
     * @param groupId  mã nhóm
     * @param accepted {@code true} nếu được duyệt vào nhóm
     * @param message  thông điệp tiếng Việt do server gửi
     */
    default void onGroupJoinResult(String groupId, boolean accepted, String message) {
        // Không quan tâm.
    }

    /**
     * Yêu cầu vừa gửi bị server từ chối.
     *
     * @param code    mã lỗi, ví dụ {@code "TARGET_NOT_FOUND"}
     * @param message thông điệp tiếng Việt do server gửi
     */
    default void onError(String code, String message) {
        // Không quan tâm.
    }
}
