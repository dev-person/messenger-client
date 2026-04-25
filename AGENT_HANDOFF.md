# AGENT_HANDOFF — передача работы следующему AI-агенту

Этот файл — полная выжимка состояния проекта на момент передачи работы.
Предыдущий агент (Claude через Cowork mode) выполнил несколько итераций,
из которых последняя — незавершённая. Ниже всё что сделано, всё что
осталось, и точная инструкция для следующих шагов.

**Проект:** Messenger for Android (Grizzly) — мессенджер с E2E
шифрованием. Клиент на Kotlin + Jetpack Compose, сервер на Go.

**Правила проекта** (важно соблюдать при продолжении):
- Чистый код, без костылей, своевременный рефакторинг
- Комментарии на русском
- Перед разработкой — изучать md-файлы; при изменениях идей/архитектуры
  обновлять их
- Бамп версии — только по команде пользователя, через `./deploy.sh` с
  предварительной записью изменений в [CHANGELOG.md](CHANGELOG.md)
- Пользователь сам запускает деплой; выдавать ему готовые команды

---

## 0. Краткий обзор проекта

- Адрес продакшена: `https://grizzly-messenger.ru`
- Сервер: `root@80.87.103.108`, Docker Compose в `/opt/messenger-server/`
- SSH-ключ пользователя (Mac): `~/.ssh/id_rsa`, passphrase `058214`
- Деплой клиента: `./deploy.sh onlyClient` (парсит CHANGELOG.md, собирает APK, заливает, регистрирует версию в БД)
- Деплой сервера: `./deploy.sh` (git pull + docker compose build + restart)
- Server git repo — **отдельный**: `git -C server/ …`
- Версия на момент сдачи: **1.0.67 alfa** уже задеплоена и бамп сделан
  (versionCode=67 в `app/build.gradle.kts`)

Ключевые файлы архитектуры:
- [ARCHITECTURE.md](ARCHITECTURE.md) — общий обзор
- [GROUPS_DESIGN.md](GROUPS_DESIGN.md) — детальный план системы групп
  (1.0.68 → 1.0.69 → 1.0.70)
- [CHANGELOG.md](CHANGELOG.md) — история версий
- [forTask/deployment.md](forTask/deployment.md) — все команды деплоя

---

## 1. Что сделано и задеплоено: 1.0.67 alfa

Уже в бою. Если будешь рефакторить — не ломай поведение.

**Экран авторизации** (`presentation/ui/auth/AuthScreen.kt`):
- `statusBarsPadding()` + верхний отступ (`40dp` / `20dp` при клавиатуре) —
  логотип не заезжает под вырез камеры
- Плавное сжатие шапки при появлении клавиатуры (animateDpAsState)
- Единая высота полей `FieldHeight = 56.dp` для всех инпутов (страна,
  телефон, OTP, пароль)
- `verticalScroll(rememberScrollState())` + `imePadding()` —
  на маленьких экранах кнопка «Продолжить» гарантированно видна

**Диалог** (`presentation/ui/chat/ChatScreen.kt`):
- LazyColumn переведён на `reverseLayout = true` — при открытии сразу
  видно последнее сообщение, без дёрганья «загрузили верх → скроллим вниз»
- `ChatItem.DateHeader` — отдельный тип в sealed interface для дата-
  сепараторов (раньше рисовались инлайново внутри `itemsIndexed`)
- `reversedItems = chatItems.asReversed()` — список, передаваемый в
  LazyColumn. Индекс 0 = последнее сообщение (визуально внизу)
- Loading slot для старых сообщений — стабильная высота `40.dp` когда
  `hasOlderMessages == true` (появление/исчезновение не двигает список)
- Префетч старых сообщений при `lastVisibleItemIndex >= size - 5`
- Автоскролл к новому сообщению: всегда к своему; чужое — только если
  пользователь уже у низа (`firstVisibleItemIndex == 0`)

**Большие стикеры** (`utils/ImageCodec.kt`):
- `MAX_RAW_BYTES` поднят с 600 КБ до 1,2 МБ
- Новый метод `loadAndCompressDetailed(): LoadResult` с sealed-результатом
  `Ok | TooLarge | Failed` — вместо silent null
- ChatScreen показывает snackbar при TooLarge с указанием размера и лимита

**Онлайн-статус** (`SignalingClient.kt`, `ChatViewModel.kt`):
- `desiredPresence: Boolean?` буферизуется; при onOpen WebSocket'а
  повторно отправляется серверу — решает race при быстром реконнекте
- `ChatRepository.refreshUserProfile(userId)` — при открытии чата
  тянется актуальный профиль собеседника через REST как fallback

**Плавное переключение темы** (`presentation/theme/`):
- `animatedColorScheme()` — обёртка над всеми 37 полями `ColorScheme`
  через `animateColorAsState`
- Pixel-perfect circular reveal при переходе light↔dark:
  - `ThemeTransition.kt` — singleton с `StateFlow<Spec?>` хранящим `ImageBitmap` снимка
  - `SettingsScreen` при клике вызывает `view.draw(Canvas(bitmap))` через `LocalView.current` — захват **перед** сменой темы
  - `Theme.kt` overlay `Canvas` рисует bitmap через `drawImage` с
    `clipPath` (EvenOdd rect + растущий oval) — 850 мс с FastOutSlowIn
  - **Важно**: пробовали через `rememberGraphicsLayer().record()` —
    не работало в обёртке всего приложения (layer оставался пустым).
    Работающий вариант — через `view.draw(Canvas)` на native Android
    Bitmap + `asImageBitmap()`
- Триггер reveal только при `current.isDark != scheme.isDark` (внутри
  одной «светлоты» — просто crossfade цветов)

**Graceful degradation блюра на Android <12**:
- `ChatTopBar`: alpha `0.72` на API 31+, `0.96` на <31
- `MessageInputBar`: alpha `0.88` на API 31+, `0.97` на <31
- `SettingsScreen.BlurUnsupportedHint` — инфо-плашка с иконкой Info,
  видна только на API <31, между секциями «Цветовая схема» и
  «Фон диалога»

**Исправления на сервере (1.0.67)**: не было, только клиентские.

---

## 2. ПОЛУСДЕЛАНО: 1.0.68 — Group Fundamentals

Версия в `app/build.gradle.kts` **ещё не поднята** — остаётся 67.
Серверная часть 1.0.68 написана но **НЕ задеплоена**. Клиентская часть
1.0.68 **не начата**.

### 2.1 Согласованный scope 1.0.68

Из [GROUPS_DESIGN.md](GROUPS_DESIGN.md):
- Создание группы (название, аватар, первичные участники)
- E2E через **Sender Keys** (Signal-подобно, упрощённо без ratchet)
- Только по инвайту — публичных ссылок/поиска нет
- Кик участника CREATOR'ом или ADMIN'ом
- Редактирование названия/аватара
- Роли: CREATOR + ADMIN + MEMBER
- Лимит 50 участников
- **Каскадная передача CREATOR при выходе**: сначала случайный
  ADMIN → если нет, случайный MEMBER → если никого, группа удаляется.
  Каскад рекурсивный: новый CREATOR уходит → снова выбор
- История сообщений для новых участников — **отложено в 1.0.69**
- Групповые звонки (SFU) — **отложено в 1.0.70**

### 2.2 Изменения серверной части (уже в коде, не задеплоено)

**Миграция** (в `server/internal/database/postgres.go` → `runMigrations`):
- Добавлены 5 новых операций, все idempotent (`ADD COLUMN IF NOT EXISTS`,
  `CREATE TABLE IF NOT EXISTS`, DO-блок с проверкой CHECK constraint)
- `chat_members.role` — TEXT DEFAULT 'MEMBER' CHECK IN ('CREATOR','ADMIN','MEMBER')
- `group_sender_keys` — новая таблица (chat_id, owner_id, recipient_id, epoch, encrypted_key)
- `messages.group_epoch` INT NULL — под каким epoch зашифровано
- `chats.current_epoch` INT DEFAULT 0 — активный epoch группы

Дублирующий SQL-файл для документации:
`server/internal/database/migrations/007_groups.sql`

**Модели** (`server/internal/models/models.go`):
- Константы `RoleCreator`, `RoleAdmin`, `RoleMember`
- `User.Role` (string, `omitempty`) — заполняется в `populateMembers`
- `Chat.CurrentEpoch` (int), `Chat.MyRole` (string, `omitempty`)
- `Message.GroupEpoch` (*int, `omitempty`)

**Requests** (`server/internal/models/requests.go`):
- `CreateGroupChatRequest.AvatarURL *string` — добавлено
- `UpdateGroupInfoRequest` (Title/AvatarURL nullable)
- `AddGroupMemberRequest` (UserID)
- `ChangeMemberRoleRequest` (Role)
- `SenderKeyEntry` (OwnerID, RecipientID, Epoch, EncryptedKey)
- `UploadSenderKeysRequest` (Epoch, Entries)

**ChatRepo** (`server/internal/repository/chat_repo.go`):
- Импорт `"errors"` и `"github.com/jackc/pgx/v5"` добавлен
- `CreateGroup(ctx, creatorID, title, memberIDs, avatarURL *string)` —
  сигнатура изменилась (5-й аргумент). Creator получает роль CREATOR
- `ListForUser`/`GetByID` — SELECT дополнен `current_epoch`, `role`
- `populateMembers` — читает `cm.role` в `u.Role`
- **Новые методы**:
  - `GetMemberRole(ctx, chatID, userID) (string, error)` — пустая строка если не член
  - `CanManageGroup(ctx, chatID, userID) (bool, error)` — `role in (CREATOR, ADMIN)`
  - `UpdateGroupInfo(ctx, chatID, title, avatarURL *string)` — partial update через COALESCE
  - `incrementEpoch(ctx, tx pgx.Tx, chatID) (int, error)` — инкремент внутри транзакции
  - `AddMember(ctx, chatID, userID) (newEpoch int, error)` — advisory-lock + идемпотентно
  - `RemoveMember(ctx, chatID, targetID) (newEpoch int, error)` — запрет кикать CREATOR
  - `LeaveGroup(ctx, chatID, userID) (*LeaveGroupResult, error)` —
    каскадная передача через `random()` ADMIN → MEMBER → удалить группу
  - `ChangeMemberRole(ctx, chatID, targetID, newRole string) error` —
    только CREATOR может вызывать, запрет менять роль CREATOR'а

**GroupSenderKeyRepo** (`server/internal/repository/group_sender_key_repo.go`,
новый файл):
- `Upload(ctx, chatID, ownerID, epoch, entries)` — batch с
  `ON CONFLICT DO UPDATE` для идемпотентности
- `FetchForRecipient(ctx, chatID, recipientID, epochFilter *int)` —
  SQL `($3::INT IS NULL OR epoch = $3::INT)` для опционального фильтра
- `DeleteByChat` — явная очистка (CASCADE и так работает, но полезен helper)

**GroupHandler** (`server/internal/handler/group_handler.go`, новый файл):
- 7 методов: `UpdateInfo`, `AddMember`, `RemoveMember`, `Leave`,
  `ChangeRole`, `UploadSenderKeys`, `FetchSenderKeys`
- `requireManager(w, r, chatID, userID)` — гард на CREATOR/ADMIN
- `broadcastToMembers(ctx, chatID, envelope)` — рассылка WS всем
- Использует `response.Forbidden(w, msg)` — variadic сигнатура

**response.Forbidden** (`server/pkg/response/response.go`):
- Сигнатура изменена: `func Forbidden(w http.ResponseWriter, msg ...string)`.
  Обратно совместима с существующими вызовами `Forbidden(w)` в
  `message_handler.go`

**Маршруты** (`server/cmd/server/main.go`):
- Добавлен `groupSenderKeyRepo := repository.NewGroupSenderKeyRepo(db)`
- Добавлен `groupHandler := handler.NewGroupHandler(chatRepo, groupSenderKeyRepo, userRepo, hub)`
- 7 новых роутов внутри protected group:
  ```go
  r.Patch("/v1/chats/{chatId}",                       groupHandler.UpdateInfo)
  r.Post("/v1/chats/{chatId}/members",                groupHandler.AddMember)
  r.Delete("/v1/chats/{chatId}/members/{userId}",     groupHandler.RemoveMember)
  r.Post("/v1/chats/{chatId}/leave",                  groupHandler.Leave)
  r.Patch("/v1/chats/{chatId}/members/{userId}/role", groupHandler.ChangeRole)
  r.Post("/v1/chats/{chatId}/sender-keys",            groupHandler.UploadSenderKeys)
  r.Get("/v1/chats/{chatId}/sender-keys",             groupHandler.FetchSenderKeys)
  ```
- `chatHandler.CreateGroup` уже обновлён — вызывает
  `chatRepo.CreateGroup(ctx, userID, req.Title, req.MemberIDs, req.AvatarURL)`

**Hub** (`server/internal/websocket/hub.go`):
- `shouldQueueOffline` расширен — `group_sender_key_ready` НЕ кладётся
  в pending-очередь (клиент при реконнекте зафетчит актуальные ключи
  через `GET /sender-keys` — дубль через pending избыточен)

**WebSocket события, которые генерирует GroupHandler** (автоматически
broadcast через `broadcastToMembers`):
- `group_info_updated` — PATCH /chat
- `group_member_added` — POST /members
- `group_member_removed` — DELETE /members/{id} или self-leave
- `group_role_changed` — PATCH /role или при каскадной передаче CREATOR
- `group_deleted` — последний ушёл, группа снесена
- `group_sender_key_ready` — targeted (только recipient'у), не в offline-очереди

**Обратная совместимость — проверено**:
- Direct-чаты работают штатно (роль не читается для DIRECT)
- Клиент 1.0.67 получает новые JSON-поля (`currentEpoch`, `myRole`,
  `role` в members) — они `omitempty`, клиент их просто игнорирует
  при парсинге
- Клиент 1.0.67 получает новые WS-типы — `SignalingClient.parseAndEmit`
  логирует unknown и возвращает без падения

### 2.3 Клиентская часть 1.0.68 — НЕ НАЧАТА

Полный список задач из [GROUPS_DESIGN.md §1.5](GROUPS_DESIGN.md):

**Room-миграция + Entity + DAO**:
- `ChatEntity`: добавить `role: String = "MEMBER"`, `currentEpoch: Int = 0`
- Новый `GroupSenderKeyEntity`:
  ```kotlin
  @Entity(tableName = "group_sender_keys", primaryKeys = ["chatId","ownerId","epoch"])
  data class GroupSenderKeyEntity(
      val chatId: String,
      val ownerId: String,
      val epoch: Int,
      val senderKey: String,  // расшифрованный ключ в base64 (Android Keystore обёртки пока не нужны)
      val createdAt: Long,
  )
  ```
- Новый `GroupSenderKeyDao` — upsert, getByChatAndOwner, deleteByChat
- В `AppDatabase` — поднять версию + migration
- В `DomainMapping`: `Chat.currentEpoch`, `Chat.myRole`, `User.role`

**GroupCryptoManager** (новый класс в `utils/`):
```kotlin
fun generateSenderKey(): ByteArray  // 32 случайных байта
fun wrapSenderKey(senderKey, recipientPublicKey, myPrivate, chatId): ByteArray
// ECDH(myPrivate, recipientPublic) → HKDF(..., salt=chatId) → AES-GCM(senderKey)
fun unwrapSenderKey(encrypted, ownerPublicKey, myPrivate, chatId): ByteArray
fun encryptGroupMessage(plaintext, senderKey, messageId): ByteArray
// HKDF(senderKey, salt=messageId, info="group-msg-v1") → AES-GCM
fun decryptGroupMessage(encrypted, senderKey, messageId): ByteArray
```
Использует тот же BouncyCastle что и `CryptoManager`.

**Repository-слой**:
Новый интерфейс `GroupRepository` (или методы в существующем
`ChatRepository`) — в `domain/repository/`:
```kotlin
suspend fun createGroup(title: String, avatarBytes: ByteArray?, memberIds: List<String>): Result<Chat>
suspend fun addMember(chatId: String, userId: String): Result<Unit>
suspend fun removeMember(chatId: String, userId: String): Result<Unit>
suspend fun leaveGroup(chatId: String): Result<Unit>
suspend fun updateGroupInfo(chatId: String, title: String?, avatarUrl: String?): Result<Unit>
suspend fun changeRole(chatId: String, userId: String, role: ChatRole): Result<Unit>
suspend fun uploadSenderKeys(chatId: String, epoch: Int, entries: List<SenderKeyEntry>): Result<Unit>
suspend fun fetchSenderKeys(chatId: String, epoch: Int?): Result<List<SenderKeyEntry>>
```
Плюс реализация в `data/repository/GroupRepositoryImpl.kt` — HTTP через
Retrofit и запись в Room.

**Retrofit API** (`data/remote/api/MessengerApi.kt`):
Добавить эндпоинты в интерфейс:
```kotlin
@PATCH("chats/{chatId}")
suspend fun updateGroupInfo(@Path("chatId") id: String, @Body body: UpdateGroupInfoDto): ApiResponse<Unit>
@POST("chats/{chatId}/members")
suspend fun addMember(@Path("chatId") id: String, @Body body: AddMemberDto): ApiResponse<EpochResponse>
@DELETE("chats/{chatId}/members/{userId}")
suspend fun removeMember(@Path("chatId") cid: String, @Path("userId") uid: String): ApiResponse<EpochResponse>
@POST("chats/{chatId}/leave")
suspend fun leaveGroup(@Path("chatId") id: String): ApiResponse<LeaveResponse>
@PATCH("chats/{chatId}/members/{userId}/role")
suspend fun changeRole(@Path("chatId") cid: String, @Path("userId") uid: String, @Body body: ChangeRoleDto): ApiResponse<Unit>
@POST("chats/{chatId}/sender-keys")
suspend fun uploadSenderKeys(@Path("chatId") id: String, @Body body: UploadSenderKeysDto): ApiResponse<Unit>
@GET("chats/{chatId}/sender-keys")
suspend fun fetchSenderKeys(@Path("chatId") id: String, @Query("epoch") epoch: Int?): ApiResponse<List<SenderKeyEntryDto>>
```
И DTOs в `data/remote/api/dto/Dtos.kt`.

**UI экраны (Compose)**:

1. `presentation/ui/groups/CreateGroupScreen.kt`:
   - Поле «Название группы»
   - Кропер аватара — переиспользовать `AvatarCropDialog` из
     `presentation/ui/components/`
   - Список контактов с чекбоксами + поиск (фильтрация `ContactsViewModel.contacts`)
   - Кнопка «Создать»
   - `CreateGroupViewModel`: собирает title + avatarBytes + memberIds,
     вызывает `groupRepository.createGroup()`, при успехе генерирует
     sender key, шифрует его для каждого участника через
     `GroupCryptoManager.wrapSenderKey()`, POSTит `/sender-keys`,
     сохраняет свой sender key в локальную БД

2. `presentation/ui/groups/GroupInfoScreen.kt`:
   - Аватар + название (редактируемо при `myRole in (CREATOR,ADMIN)`)
   - Список участников с ролями (иконка/бейдж рядом с именем)
   - Кнопка «Добавить участника» — открывает пикер контактов, при
     выборе → `addMember()` + ротация своего sender key
   - На каждом участнике: меню «Сделать админом» (если `myRole == CREATOR`
     и у цели `role != ADMIN`), «Исключить» (если `myRole in (CREATOR,ADMIN)`
     и цель `!= CREATOR && != self`)
   - Кнопка «Выйти из группы» внизу

3. `ChatListScreen` — FAB «+ Новая группа» или пункт меню в top-bar

4. `ChatScreen` — адаптация:
   - Шапка: для `ChatType.GROUP` клик по title → `GroupInfoScreen`
   - `sendMessage()` — для GROUP использует групповое шифрование:
     - Берёт свой sender key из БД по `(chatId, myUserId, currentEpoch)`
     - `encryptGroupMessage(plaintext, senderKey, messageId)`
     - Шлёт с `groupEpoch = currentEpoch`
   - Приём: `decryptGroupMessage()` — берёт sender key по
     `(chatId, senderId, epoch)`, если нет — fetchSenderKeys через REST

**Обработка WS-событий групп** (`service/IncomingMessageHandler.kt`):
- `group_info_updated` → обновить ChatEntity (title, avatarUrl)
- `group_member_added` → если это я — пулл chat через syncChats + запросить sender keys;
  если другой — ротировать свой sender key и отправить новому через `group_sender_key_share`
- `group_member_removed` → если это я — скрыть чат; иначе ротировать ключи
- `group_role_changed` → обновить роль в members
- `group_deleted` → удалить ChatEntity локально
- `group_sender_key_ready` → fetchSenderKeys для конкретного (ownerId, epoch)

**Новые типы в `SignalingClient.SignalingEvent`**:
```kotlin
data class GroupInfoUpdated(val chatId: String, val title: String?, val avatarUrl: String?)
data class GroupMemberAdded(val chatId: String, val userId: String, val epoch: Int)
data class GroupMemberRemoved(val chatId: String, val userId: String, val epoch: Int)
data class GroupRoleChanged(val chatId: String, val userId: String, val role: String)
data class GroupDeleted(val chatId: String)
data class GroupSenderKeyReady(val chatId: String, val ownerId: String, val epoch: Int)
```
Плюс парсинг в `parseAndEmit`.

---

## 3. Следующий шаг СРАЗУ — деплой серверной части 1.0.68

Вся серверная работа сделана, но не закоммичена и не задеплоена.

**Из `~/.projects/messengerAndroid`**:

```bash
# 1. Посмотреть diff что я наредактировал
git -C server/ status
git -C server/ diff --stat

# 2. Закоммитить сервер (server/ — отдельный git repo!)
git -C server/ add -A
git -C server/ commit -m "feat(groups): фундамент групповых чатов для 1.0.68

- миграция 007: role в chat_members, таблица group_sender_keys,
  current_epoch в chats, group_epoch в messages
- GroupSenderKeyRepo (Upload/Fetch)
- ChatRepo: GetMemberRole, CanManageGroup, UpdateGroupInfo,
  AddMember, RemoveMember, LeaveGroup (каскадная передача CREATOR),
  ChangeMemberRole, epoch-ротация под advisory-lock'ом
- GroupHandler: 7 endpoints (PATCH /chat, add/remove member,
  leave, role, upload/fetch sender-keys)
- WS events: group_info_updated/member_added/member_removed/
  role_changed/deleted/sender_key_ready
- response.Forbidden теперь variadic
- CreateGroup: + avatarURL, creator получает CREATOR role

Обратно совместимо: старые endpoints не тронуты, миграции
idempotent (IF NOT EXISTS / ADD COLUMN DEFAULT), клиент 1.0.67
продолжает работать штатно."
git -C server/ push origin main

# 3. Деплой (SSH на сервер + git pull + docker rebuild)
./deploy.sh
```

**После деплоя — проверить миграции**:

```bash
# логи: должны быть "✓ DB migrations applied" без ошибок
ssh -i ~/.ssh/id_rsa root@80.87.103.108 \
  "docker logs messenger-server-server-1 --tail 50"

# структура таблиц
ssh -i ~/.ssh/id_rsa root@80.87.103.108 \
  "docker exec messenger-server-postgres-1 psql -U postgres -d messenger -c '\d chat_members' \
   && docker exec messenger-server-postgres-1 psql -U postgres -d messenger -c '\d group_sender_keys' \
   && docker exec messenger-server-postgres-1 psql -U postgres -d messenger -c '\d chats'"
```

Ожидаемые проверки:
- `chat_members.role` TEXT NOT NULL DEFAULT 'MEMBER' с CHECK
- `group_sender_keys` существует, 7 колонок (id, chat_id, owner_id, recipient_id, epoch, encrypted_key, created_at)
- `chats.current_epoch` INT NOT NULL DEFAULT 0
- `messages.group_epoch` INT (nullable)

**Smoke test — direct чаты не сломаны**:
- Пользователь: открыть существующий direct-чат в клиенте 1.0.67
- Отправить сообщение, получить от собеседника
- Позвонить — аудио/видео
- Всё должно работать как раньше

Если direct сломан → `git -C server/ revert HEAD && git -C server/ push && ./deploy.sh`
и сообщить следующему агенту что мигрировать пошло не так.

---

## 4. Что делать после успешного серверного деплоя

1. Начать клиентскую часть 1.0.68 — порядок шагов из
   [GROUPS_DESIGN.md §1.6](GROUPS_DESIGN.md):
   - Шаг 5: Room migration + GroupSenderKeyEntity + Dao
   - Шаг 6: GroupCryptoManager + unit-тест
   - Шаг 7: Расширение ChatRepository + Retrofit-методы + DI
   - Шаг 8: CreateGroupScreen + CreateGroupViewModel
   - Шаг 9: GroupInfoScreen + GroupInfoViewModel
   - Шаг 10: Интеграция send/receive в существующем ChatViewModel для ChatType.GROUP
   - Шаг 11: QA — 7 сценариев из §5 GROUPS_DESIGN.md
   - Шаг 12: Записать 1.0.68 в CHANGELOG.md
   - Шаг 13: Пользователь даст команду «бампим» → поднять
     `versionCode = 68`, `versionName = "1.0.68 alfa"` в
     `app/build.gradle.kts`, запустить `./deploy.sh onlyClient`

2. **Ключевые развилки уже решены пользователем** (не переспрашивать):
   - Sender Keys (не multi-recipient)
   - Лимит 50 участников
   - Только по инвайту (не публичные, без join-ссылок)
   - Каскадная передача CREATOR через `random()` среди ADMIN → MEMBER
   - История для новых участников — **только 1.0.69**, в 1.0.68 новый
     участник видит **только новые сообщения**
   - Групповые звонки — **только 1.0.70** (SFU, отдельный сервис)

---

## 5. Известные тонкости / грабли

**Конфликт `drawContent()` и `graphicsLayer.record` в Compose 1.7.3**:
В Theme.kt пробовался паттерн Google из docs.android.com —
`graphicsLayer.record { this@drawWithContent.drawContent() }` —
не захватывал пиксели в layer когда оборачивался ВЕСЬ app-content (MaterialTheme → Box → content()).
При клипе `drawLayer(layer)` показывал пусто. Почему — до конца не
выяснили, предположительно `drawContent()` внутри record блока
роутит рисование в outer-canvas, а не в layer.

**Фикс**: для reveal-анимации темы ушли на `view.draw(Canvas(bitmap))`
через `LocalView.current` — работает стабильно. Решение зафиксировано
в `ThemeTransition.startReveal(view: View)`.

Для групповых операций `graphicsLayer` нигде не используется — так что
этот грабль не всплывёт.

**Advisory-lock на chat_id в транзакциях**:
`pg_advisory_xact_lock(hashtext($1)::bigint)` — `hashtext` возвращает
int4, приведение к bigint обязательно (advisory lock принимает bigint).

**pgx ErrNoRows**:
В `GetMemberRole` используется `errors.Is(err, pgx.ErrNoRows)` —
не сравнивать `err.Error() == "no rows in result set"`, это
ненадёжно (форматирование может измениться между версиями pgx).

**Room-миграция при добавлении поля в ChatEntity**:
Не забывать поднять `version` в `@Database(version = N)` и написать
migration. В проекте используется destructive fallback в dev-сборке
но в релизе — нужна явная миграция.

---

## 6. Карта изменённых файлов в этой сессии

### Клиент (1.0.67, уже задеплоено)
- `app/src/main/java/com/secure/messenger/presentation/ui/auth/AuthScreen.kt`
- `app/src/main/java/com/secure/messenger/presentation/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/secure/messenger/presentation/viewmodel/ChatViewModel.kt`
- `app/src/main/java/com/secure/messenger/data/remote/websocket/SignalingClient.kt`
- `app/src/main/java/com/secure/messenger/domain/repository/ChatRepository.kt`
- `app/src/main/java/com/secure/messenger/data/repository/ChatRepositoryImpl.kt`
- `app/src/main/java/com/secure/messenger/utils/ImageCodec.kt`
- `app/src/main/java/com/secure/messenger/presentation/theme/Theme.kt`
- `app/src/main/java/com/secure/messenger/presentation/theme/ThemeTransition.kt` (новый)
- `app/src/main/java/com/secure/messenger/presentation/ui/main/MainActivity.kt`
- `app/src/main/java/com/secure/messenger/presentation/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/secure/messenger/presentation/ui/settings/SettingsViewModel.kt`
- `app/build.gradle.kts` (versionCode=67, versionName="1.0.67 alfa")
- `CHANGELOG.md` (запись 1.0.67)

### Сервер (1.0.68, в коде, НЕ ЗАДЕПЛОЕНО)
- `server/internal/database/postgres.go` (миграции)
- `server/internal/database/migrations/007_groups.sql` (документация)
- `server/internal/models/models.go` (User.Role, Chat.CurrentEpoch/MyRole, Message.GroupEpoch)
- `server/internal/models/requests.go` (DTO'шки групп)
- `server/internal/repository/chat_repo.go` (новые методы + импорт errors/pgx)
- `server/internal/repository/group_sender_key_repo.go` (новый файл)
- `server/internal/handler/chat_handler.go` (CreateGroup вызов с avatarURL)
- `server/internal/handler/group_handler.go` (новый файл)
- `server/internal/websocket/hub.go` (group_sender_key_ready исключение)
- `server/pkg/response/response.go` (Forbidden variadic)
- `server/cmd/server/main.go` (регистрация GroupHandler + 7 роутов)

### Документация
- `ARCHITECTURE.md` (ссылка на GROUPS_DESIGN.md)
- `GROUPS_DESIGN.md` (новый файл, детальный план групп)
- `AGENT_HANDOFF.md` (этот файл)

---

## 7. Контакты/доступы (из forTask/deployment.md)

| Что | Где |
|---|---|
| SSH server | `root@80.87.103.108`, ключ `~/.ssh/id_rsa`, passphrase `058214` |
| ADMIN_SECRET | `4e590ac97dadaf4e56ef099dfb8b68e6` → https://grizzly-messenger.ru/admin/ |
| DB password | `f5f7d56fe2df450c6e189f88` |
| Server git | `git@github.com:dev-person/messenger-server.git` |
| Domain | `grizzly-messenger.ru` |
| SSL | Let's Encrypt, авто-обновление через certbot.timer |
| API base | `https://grizzly-messenger.ru/v1/` |
| WS base | `wss://grizzly-messenger.ru/ws` |

---

*Файл сгенерирован автоматически в момент передачи работы. Когда
закончите — просто удалите этот файл: `rm AGENT_HANDOFF.md`.*
