# MiniChat — Giai đoạn 3: Phòng chung, chat riêng 1-1 và chat nhóm

Ứng dụng chat tối giản dùng **Java thuần**: `ServerSocket`/`Socket` + TCP + Swing,
không dùng thư viện ngoài, không cần cơ sở dữ liệu.

Giai đoạn 3 giữ nguyên **phòng chat chung** của giai đoạn 2 và bổ sung:

- **Chat riêng 1-1**: chỉ người gửi, người nhận và Server thấy nội dung.
- **Chat nhóm**: nhóm được tạo khi có **ít nhất 3 thành viên**; chỉ các thành viên
  của nhóm nhận được tin nhắn nhóm.
- **Danh sách client đang online** (`USERS`) để chọn người chat riêng.
- Thông báo lỗi có mã (`ERR|code|message`) khi yêu cầu không hợp lệ.
- Server ghi nhật ký nội dung tin nhắn (yêu cầu của đề: Server thấy được tin nhắn).

## 1. Tính năng theo giai đoạn

| Giai đoạn | Nội dung |
|---|---|
| 1 | Một client kết nối, server echo lại tin nhắn |
| 2 | Nhiều client trong **một phòng chung**, broadcast tới tất cả, thông báo tham gia/rời phòng |
| 3 | Giữ phòng chung + **chat riêng 1-1** + **chat nhóm** + danh sách online + mã lỗi |

## 2. Quy tắc chat riêng và chat nhóm

### Chat riêng 1-1

- Client chọn một tên trong danh sách **Client đang online** ở tab **Chat riêng**.
- Server chỉ gửi tin tới đúng hai client: người gửi (bản echo) và người nhận.
- Không cho gửi cho chính mình (`ERR|SELF_TARGET`).
- Người nhận không tồn tại/đã offline: `ERR|TARGET_NOT_FOUND`.
- Lịch sử riêng được giữ theo từng người trong phiên làm việc (tối đa 300 dòng).

### Chat nhóm

- Tạo nhóm bằng cách chọn **ít nhất 2 client khác** (tổng cộng ≥ 3 thành viên).
- Quy tắc số thành viên tối thiểu **chỉ kiểm tra tại thời điểm tạo nhóm**
  (`Protocol.MIN_GROUP_MEMBERS = 3`). Sau khi tạo:
  - nhóm còn 2 thành viên vẫn chat bình thường;
  - nhóm còn 1 thành viên vẫn gửi được và nhận bản echo của chính mình;
  - nhóm không còn thành viên nào sẽ bị Server tự xóa.
- Chỉ thành viên nhóm nhận được `GMSG`; người ngoài gửi vào nhóm bị từ chối
  (`ERR|NOT_MEMBER`) và không ai trong nhóm nhận được nội dung đó.
- Giới hạn hiện tại: tối đa **20 thành viên/nhóm**, tối đa **10 nhóm/client**.
- Người tạo nhóm (`owner`) chỉ dùng để ghi nhật ký, không có quyền đặc biệt.
- Giai đoạn 3 **chưa có** thêm thành viên sau khi tạo, đổi tên nhóm hay xóa nhóm
  (ngoài việc rời nhóm); nhóm tự xóa khi rỗng.
- Lịch sử nhóm được giữ theo từng nhóm trong phiên (tối đa 300 dòng).

## 3. Cấu trúc project

```
D:\PTUNDHV\Minichat_ok\
├── src\
│   └── minichat_ok\
│       ├── common\
│       │   └── Protocol.java           # Hằng số + dựng/phân tích dòng giao thức
│       ├── server\
│       │   ├── ChatServer.java         # ServerSocket, registry client, DM, nhóm, broadcast
│       │   ├── ClientHandler.java      # Đọc/ghi dữ liệu và xử lý lệnh của 1 client
│       │   └── Group.java              # Model nhóm: id, owner, tập thành viên
│       ├── client\
│       │   ├── ChatClient.java         # Socket client, luồng nhận dữ liệu riêng
│       │   └── ChatClientListener.java # Interface callback sự kiện (default methods)
│       └── ui\
│           ├── ServerFrame.java        # Giao diện Server (main)
│           └── ClientFrame.java        # Giao diện Client 3 tab (main)
├── out\                                # .class sau khi biên dịch (tự sinh)
├── data\users.txt                      # để trống, dành cho giai đoạn sau
└── README.md
```

## 4. Môi trường và biên dịch

Yêu cầu: **JDK 17 trở lên** (đã kiểm thử với JDK 27), `java` và `javac` cùng phiên bản.
Mở project `D:\PTUNDHV\Minichat_ok` bằng VS Code với Extension Pack for Java, hoặc
biên dịch bằng PowerShell:

```powershell
$files = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -Xlint:all -d out $files
```

Không có lỗi/cảnh báo nghĩa là biên dịch sạch.

## 5. Chạy thử

Mở 4 terminal tại thư mục gốc project.

**Terminal 1 — Server:**

```powershell
java -cp out minichat_ok.ui.ServerFrame
```

Giữ cổng `5000` → bấm **Khởi động**. Trạng thái hiển thị số client và số nhóm,
ví dụ `Đang chạy - cổng 5000 - 0 client - 0 nhóm`.

**Terminal 2, 3, 4 — Client:**

```powershell
java -cp out minichat_ok.ui.ClientFrame
```

Mỗi cửa sổ nhập IP `127.0.0.1`, cổng `5000` → **Kết nối**. Client gửi yêu cầu tham
gia; cửa sổ **Server** hiện yêu cầu đó ở khung **Yêu cầu kết nối** (bên phải) kèm
hai nút **Duyệt** và **Chặn**. Chỉ khi bấm **Duyệt**, Server mới cấp tên `Client-1`,
`Client-2`, `Client-3`… và danh sách online mới cập nhật; bấm **Chặn** thì client
nhận lý do từ chối và bị đóng kết nối.

### Kịch bản kiểm thử nhanh

1. **Phòng chung**: ở tab **Phòng chung**, gửi `xin chào` → cả 3 client đều thấy.
2. **Chat riêng**: Client-1 mở tab **Chat riêng**, chọn `Client-2`, gửi
   `bí mật` → chỉ Client-1 và Client-2 thấy; Client-3 không thấy.
3. **Tạo nhóm**: Client-1 mở tab **Nhóm** → **Tạo nhóm** → chọn `Client-2` và
   `Client-3` → cả 3 nhận nhóm `G1`.
4. **Chat nhóm**: Client-2 gửi tin trong nhóm `G1` → Client-1 và Client-3 thấy.
5. **Rời nhóm**: Client-3 bấm **Rời nhóm** → Client-1 và Client-2 nhận thông báo
   `Client-3 đã rời nhóm`; Client-1 và Client-2 vẫn chat nhóm bình thường.
6. **Nhóm rỗng**: Client-2 rời nhóm, Client-1 gửi tin vẫn nhận echo; Client-1
   ngắt kết nối → Server tự xóa nhóm, số nhóm về 0.

## 6. Giao thức (UTF-8, mỗi khung tin kết thúc bằng `\n`)

### Client → Server

| Dòng | Ý nghĩa |
|---|---|
| `JOIN` | Yêu cầu tham gia phòng chat; **bắt buộc** gửi ngay sau khi mở kết nối, trước mọi lệnh khác |
| `MSG\|nội dung` | Tin nhắn phòng chung (phát cho mọi client) |
| `PRIV\|người-nhận\|nội dung` | Tin nhắn riêng 1-1 |
| `GROUP_CREATE\|ds-thành-viên` | Tạo nhóm; danh sách phân tách bằng dấu phẩy, không gồm người tạo |
| `GMSG\|mã-nhóm\|nội dung` | Tin nhắn nhóm |
| `GLEAVE\|mã-nhóm` | Rời nhóm |
| `USERS` | Yêu cầu danh sách client đang online |
| dòng không có `\|` | Tương thích giai đoạn 1: coi là tin nhắn phòng chung |

Các loại do Server sinh ra (`JOIN_OK`, `JOIN_DENY`, `NAME`, `SYS`, `GROUP`,
`GLEFT`, `ERR`) nếu client tự gửi lên sẽ bị bỏ qua. Tiền tố lạ có chứa `|` bị từ
chối bằng `ERR|BAD_REQUEST` để tránh nội dung riêng bị phát nhầm vào phòng chung.

### Server → Client

| Dòng | Ý nghĩa |
|---|---|
| `JOIN_OK\|tên` | Server chấp nhận yêu cầu tham gia và cấp tên định danh (client chỉ vào phòng từ đây) |
| `JOIN_DENY\|lý-do` | Server từ chối yêu cầu tham gia; client đóng kết nối |
| `NAME\|tên` | Tên định danh Server cấp cho client (giữ để tương thích) |
| `MSG\|người-gửi\|nội dung` | Tin nhắn phòng chung (kể cả bản echo của người gửi) |
| `SYS\|nội dung` | Thông báo hệ thống: tham gia/rời phòng, Server dừng |
| `USERS\|a,b,c` | Danh sách client đang online |
| `PRIV\|người-gửi\|người-nhận\|nội dung` | Tin nhắn riêng (gửi cho cả hai bên) |
| `GROUP\|mã-nhóm\|ds-thành-viên` | Thông tin nhóm gửi cho từng thành viên |
| `GMSG\|mã-nhóm\|người-gửi\|nội dung` | Tin nhắn nhóm gửi cho các thành viên |
| `GLEFT\|mã-nhóm\|tên\|lý-do` | Một thành viên đã rời nhóm hoặc mất kết nối |
| `ERR\|mã-lỗi\|thông-điệp` | Yêu cầu bị từ chối |

### Mã lỗi

`TARGET_NOT_FOUND`, `SELF_TARGET`, `UNKNOWN_MEMBER`, `GROUP_TOO_SMALL`,
`GROUP_TOO_LARGE`, `GROUP_NOT_FOUND`, `NOT_MEMBER`, `TOO_MANY_GROUPS`,
`MESSAGE_TOO_LONG`, `BAD_REQUEST`.

### Luồng chấp nhận kết nối

1. Client mở TCP và gửi `JOIN` (chưa có tên, chưa được đăng ký vào phòng).
2. `ClientHandler` gọi `ChatServer.requestJoin(...)`: yêu cầu được thêm vào danh sách
   chờ duyệt (`ChatServer.getPendingJoins()`) và luồng xử lý client tạm dừng.
3. `ServerFrame` nhận danh sách qua `setJoinRequestListener(...)` và hiện mỗi yêu cầu
   thành một hàng trong khung **Yêu cầu kết nối**, gồm tên, `ip:port` và hai nút
   **Duyệt** / **Chặn** (cập nhật trên EDT, không chặn giao diện).
4. Bấm **Duyệt** (`ChatServer.approveJoin`) → Server gửi `JOIN_OK|Client-N`, đăng ký
   client, phát `SYS` + `USERS`. Bấm **Chặn** (`ChatServer.denyJoin`) → Server gửi
   `JOIN_DENY|lý-do` rồi đóng kết nối.
5. Client chờ tối đa 60 giây; Server chờ dòng `JOIN` tối đa 30 giây và chờ người
   quản trị quyết định tối đa 60 giây. Quá hạn (hoặc Server dừng) thì yêu cầu bị từ
   chối và biến khỏi danh sách chờ.
6. Khi không có giao diện (kiểm thử raw socket), `setAutoApproveJoins(true)` là mặc
   định nên mọi yêu cầu được chấp nhận ngay — nhưng client **vẫn phải** gửi `JOIN`
   trước. `ServerFrame` đặt `setAutoApproveJoins(false)` để duyệt thủ công.

## 7. Kiểm thử

Đã chạy bộ kiểm thử headless tạm (raw socket, không cần Swing): **49/49 ca đạt**,
bao gồm:

- biên dịch sạch với `javac -encoding UTF-8 -Xlint:all`;
- phòng chung broadcast tới cả 4 client; dòng raw không tiền tố vẫn hoạt động;
- chống giả mạo `NAME`/`SYS`; tiền tố lạ bị `BAD_REQUEST` và không lộ nội dung;
- chat riêng: echo cho người gửi, người nhận nhận đúng, người thứ ba không thấy,
  chặn tự gửi cho mình và người nhận không tồn tại, giữ nguyên dấu `|` trong nội dung;
- nhóm: tạo 2 người bị từ chối, tạo 3 người thành công, người ngoài không thấy
  `GROUP`/`GMSG`, người ngoài gửi bị `NOT_MEMBER`;
- nhóm giảm còn 2 người vẫn chat, còn 1 người vẫn nhận echo, thành viên rời nhóm
  không gửi được nữa, nhóm rỗng tự xóa khi người cuối ngắt kết nối;
- tin quá dài bị từ chối và không broadcast; `PRIV`/`GMSG` sai định dạng bị chặn;
- yêu cầu `USERS` trả đúng danh sách online;
- dừng server có thông báo `SYS`, khởi động lại cùng cổng thành công, số nhóm về 0
  và bộ đếm tên client được đặt lại (`Client-1`); broadcast vẫn hoạt động sau restart.

## 8. Ghi chú kỹ thuật

- Giao thức văn bản thuần UTF-8, mỗi tin là một dòng kết thúc bằng đúng `\n`
  (không dùng `println()` để tránh `\r\n` trên Windows).
- `ChatServer` giữ client trong `CopyOnWriteArrayList` (duyệt an toàn khi phát tin)
  và ánh xạ tên → handler trong `ConcurrentHashMap` (định tuyến DM theo tên).
- Mọi thao tác phát tin (broadcast, DM, nhóm) dùng chung một khóa `broadcastLock`
  để các client nhận tin **cùng thứ tự**; mỗi `ClientHandler` có khóa riêng bảo vệ
  `PrintWriter`.
- Nhóm lưu trong `ConcurrentHashMap<String, Group>`; `Group` dùng
  `CopyOnWriteArraySet` cho thành viên nên vừa phát tin vừa rời nhóm an toàn.
- `ChatServer.createGroup` kiểm tra lại toàn bộ phía Server: thành viên tồn tại,
  số lượng tối thiểu/tối đa, giới hạn số nhóm của người tạo. Client chỉ kiểm tra
  sơ bộ để báo lỗi sớm.
- `ChatClientListener` dùng **default methods**, nhờ đó constructor 4 callback của
  giai đoạn 2 vẫn tương thích; giao diện chỉ ghi đè sự kiện cần dùng.
- Mọi cập nhật Swing từ luồng nhận dữ liệu đều đi qua
  `SwingUtilities.invokeLater(...)`.
- `ClientFrame` giữ lịch sử riêng/nhóm trong `Map` và giới hạn 300 dòng mỗi hội thoại;
  tab hiển thị số tin chưa đọc khi hội thoại không được chọn.
- `stop()` phát `SYS` báo dừng (best effort), đánh thức mọi yêu cầu đang chờ duyệt,
  đóng toàn bộ handler, xóa client/nhóm và đưa số client/số nhóm về 0.
- Yêu cầu kết nối chờ duyệt dùng `ChatServer.JoinRequest` (mỗi yêu cầu một
  `CountDownLatch`), nhờ đó luồng client chỉ chờ quyết định mà không khóa giao diện.
  `ChatClient.connect()` chỉ giữ khóa đối tượng trong lúc mở kết nối, phần chờ
  `JOIN_OK`/`JOIN_DENY` nằm ngoài khóa để luồng nhận vẫn đóng được tài nguyên khi
  Server ngắt kết nối.

## 9. Giới hạn đã biết và hướng phát triển

- Chưa có đăng nhập/mật khẩu; tên do Server gán. Người quản trị Server phải bấm
  **Duyệt** thì client mới vào phòng chung (hoặc bật `setAutoApproveJoins(true)`).
- Không lưu lịch sử ra đĩa: đóng ứng dụng là mất; client vào sau chỉ thấy tin từ
  lúc mình tham gia.
- Chưa hỗ trợ thêm thành viên vào nhóm đã tạo, chuyển quyền owner, đổi tên/xóa nhóm
  thủ công (nhóm tự xóa khi rỗng).
- Một client “treo” (kết nối nhưng không đọc dữ liệu) có thể làm luồng phát tin phải
  chờ khi bộ đệm TCP đầy. Hướng nâng cấp: hàng đợi gửi riêng cho từng client kèm
  `ExecutorService` để cô lập hoàn toàn.
- Chưa có mã hóa đường truyền (TLS); phù hợp cho bài tập/mạng nội bộ, không dùng
  cho dữ liệu nhạy cảm trên Internet.

## 10. Xử lý sự cố thường gặp

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Address already in use` | Cổng 5000 đang bận | Đổi cổng ở cả Server và Client, hoặc tắt tiến trình đang dùng cổng |
| Client báo không kết nối được | Server chưa khởi động / sai IP / firewall | Khởi động Server trước; dùng `127.0.0.1` khi chạy cùng máy |
| Chữ tiếng Việt bị `?` hoặc ô vuông | File nguồn sai encoding | Lưu UTF-8 và biên dịch với `-encoding UTF-8` |
| Chat riêng không gửi được | Chưa chọn người nhận hoặc người đó đã offline | Chọn lại trong danh sách **Client đang online** |
| Nút **Tạo nhóm** báo thiếu thành viên | Chưa đủ 3 client online | Mở thêm client, đảm bảo có ít nhất 3 người |
| Client-3 không thấy tin nhắn riêng/nhóm | Đúng thiết kế: chỉ người liên quan/thành viên nhận | Không cần xử lý |
| Không thấy nhóm sau khi tạo | Chưa bấm **Tạo nhóm** hoặc chọn thiếu người | Chọn ít nhất 2 client khác rồi xác nhận |
| Đã sửa code nhưng chạy vẫn như cũ | Chưa biên dịch lại | Chạy lại `javac` hoặc nút Run của VS Code |
 #alo