# Grizzly Messenger — Группы

Документ описывает архитектуру групповых чатов. Разбит на три релиза:
**1.0.68** (фундамент), **1.0.69** (история + расширенные роли),
**1.0.70** (групповые звонки). Всё обратно совместимо с существующим
direct-чатом — старые пользователи продолжают работать без обновления.

---

## 0. Что УЖЕ ЕСТЬ на сервере

- Таблица `chats` с полем `type` (`DIRECT` | `GROUP`) и `title`, `avatar_url`.
- Таблица `chat_members` с полями `chat_id`, `user_id`, `is_pinned`, `is_muted`, `unread_count`, `joined_at`.
- `ChatRepo.CreateGroup(ctx, creatorID, title, memberIDs) -> *Chat` — уже умеет создавать группу и вставлять участников.
- `POST /chats/group` — endpoint уже выставлен.
- `deliverMessage` в WS: шлёт сообщение ВСЕМ участникам чата (см. `GetMemberIDs`). Работает и для DIRECT, и для GROUP.
- Клиент: `ChatType.GROUP`, `ChatEntity.type = "GROUP"`, UI шапка чата уже показывает «N участников» если `chat.type == GROUP`.

**Что отсутствует:**
- Ролей у участников нет (creator/admin/member).
- Sender Keys — ни на сервере, ни на клиенте.
- Endpoints добавления/удаления участников, смены роли, редактирования.
- UI создания группы, просмотра участников.

---

## 1. Релиз 1.0.68 — Group Fundamentals

### 1.1 Серверная БД — миграция `007_groups.sql`

```sql
-- 007_groups.sql: роли участников групп + sender keys для E2E

-- 1. Роль участника в групповом чате.
--    CREATOR — изначальный создатель, не может быть кикнут.
--    ADMIN  — назначен создателем, может кикать/приглашать (не CREATOR).
--    MEMBER — обычный участник.
--    Default MEMBER; миграция существующих записей не меняет поведения
--    (для DIRECT-чатов роль не читается).
ALTER TABLE chat_members
    ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'MEMBER'
    CHECK (role IN ('CREATOR', 'ADMIN', 'MEMBER'));

-- 2. Таблица sender keys для группового E2E.
--    Хранит sender key участника [user_id] внутри группы [chat_id],
--    зашифрованный (ECDH X25519 → HKDF → AES-GCM) для другого
--    участника [recipient_id]. Каждый участник хранит N-1 записей
--    (чужие sender keys для расшифровки входящих сообщений).
--
--    При ротации (join/leave/kick) создаются НОВЫЕ записи с epoch+1,
--    старые не удаляем — нужны чтобы расшифровать сообщения отправленные
--    в старом epoch и ещё лежащие в pending-очереди.
CREATE TABLE IF NOT EXISTS group_sender_keys (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id           UUID NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    -- владелец sender key (кто отправляет сообщения под ним)
    owner_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- получатель (для кого зашифрован sender key). Каждая пара owner+recipient
    -- хранится отдельной строкой.
    recipient_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- epoch растёт при каждой ротации (добавление/удаление участника)
    epoch             INT  NOT NULL DEFAULT 0,
    -- зашифрованный sender key (base64). Формат: iv(12) || ciphertext || gcmTag
    encrypted_key     TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (chat_id, owner_id, recipient_id, epoch)
);

CREATE INDEX IF NOT EXISTS idx_gsk_chat_recipient
    ON group_sender_keys(chat_id, recipient_id, epoch DESC);

-- 3. Для сообщений группы храним epoch, под которым они зашифрованы.
--    Это позволяет получателю выбрать нужный sender key (если было
--    несколько ротаций и старые ключи ещё валидны).
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS group_epoch INT DEFAULT NULL;

-- 4. Текущий (активный) epoch группы — хранится на уровне чата.
ALTER TABLE chats
    ADD COLUMN IF NOT EXISTS current_epoch INT NOT NULL DEFAULT 0;
```

Ключевые решения:
- Все `ADD COLUMN … DEFAULT`, `CREATE TABLE IF NOT EXISTS` — **безопасно для существующих данных**.
- Direct-чаты не меняются: у их участников будет `role = 'MEMBER'`, но роль для direct-чатов не проверяется.
- `group_epoch = NULL` у старых сообщений — корректно обрабатывается клиентом (direct message).

### 1.2 Sender Keys — криптографический протокол

Используем подход из Signal's Group Messaging (упрощённый без ratcheting sender keys внутри epoch'а).

**Формат sender key** — 32 случайных байта (chain key).

**Порядок при создании группы:**
1. Создатель генерирует свой sender key `SKc` (32 случайных байта).
2. Для каждого участника `U_i` (кроме себя):
   - `sharedSecret = X25519(creatorPrivate, U_i.publicKey)`
   - `wrapKey = HKDF(sharedSecret, salt=chatId, info="group-sender-key-v1")`
   - `encryptedSKc_i = AES-256-GCM(wrapKey, iv=random(12), plaintext=SKc)`
3. Создатель POST-ит на сервер `{chatId, epoch=0, owner=me, recipients=[{userId, encryptedKey}]}`.
4. Каждый участник при первом заходе в чат GET-ит свои записи из `group_sender_keys`, декрипт:
   - `sharedSecret = X25519(myPrivate, U_owner.publicKey)`
   - `wrapKey = HKDF(...)`
   - `SK_owner = AES-256-GCM-Decrypt(wrapKey, encryptedKey)`
5. SK кладётся в локальную БД клиента (таблица `group_sender_keys`).

**Порядок при добавлении участника `U_new`:**
1. Клиент-админ увеличивает `epoch → e+1` (серверный лок по chat_id чтобы не было race).
2. Генерирует НОВЫЙ свой sender key.
3. Шифрует его для ВСЕХ текущих участников (включая нового) под новым epoch.
4. POST-ит на сервер batch'ом.
5. **Остальным участникам** клиент-админ посылает WS-event `group_epoch_rotated`, они подтягивают свои новые sender keys у себя и генерируют свои новые sender keys для остальных (это распределённая ответственность — каждый участник ротирует СВОЙ sender key сам).

Упрощение для 1.0.68: при добавлении участника **только админ ротирует свой sender key** и выдаёт его новому участнику. Остальные участники не ротируют (они всё ещё используют старые sender keys, известные всем кроме нового). Новый получает их sender keys через обмен: все текущие участники отдают свои sender keys новому через WS-сообщение `group_sender_key_share`.

Это работает потому что новый участник СМОТРИТ только НОВЫЕ сообщения (старые недоступны по дизайну). Sender key других — нужны чтобы расшифровать их будущие сообщения, а не прошлые.

**Порядок при кике участника `U_kicked`:**
1. Админ ставит `epoch = e+1`.
2. Каждый оставшийся участник генерирует НОВЫЙ sender key (ротация).
3. Новые sender keys шифруются для всех оставшихся (кроме U_kicked).
4. Помечаем старые sender keys U_kicked как недействительные — сервер проверяет при отправке: если отправитель не в `chat_members` → блокируем сообщение.

**Шифрование сообщения:**
1. Генерируем `messageKey = HKDF(senderKey, salt=messageId, info="group-msg-v1")`.
2. `ciphertext = AES-256-GCM(messageKey, iv=random(12), plaintext)`.
3. Отправляется: `{chatId, messageId, senderId, epoch, encryptedContent=base64(iv||ct||tag)}`.

**Дешифровка:**
1. Получатель берёт senderKey по `(chat_id, owner=senderId, recipient=me, epoch)` из локальной БД.
2. Дальше как обычно: HKDF → AES-GCM-Decrypt.

### 1.3 Новые API endpoints

Все под `/v1/chats/{chatId}/…`, защищены JWT middleware.

| Метод | Путь | Что делает | Право |
|---|---|---|---|
| `PATCH` | `/chats/{chatId}` | Меняет title и/или avatarUrl группы | CREATOR, ADMIN |
| `POST`  | `/chats/{chatId}/members` | Добавить участника. Body: `{userId}`. Сервер добавляет в `chat_members`, возвращает новый epoch и список всех участников для загрузки их publicKeys | CREATOR, ADMIN |
| `DELETE`| `/chats/{chatId}/members/{userId}` | Кикнуть участника. Увеличивает epoch, клиенты ротируют ключи | CREATOR, ADMIN (нельзя кикнуть себя и нельзя кикнуть CREATOR) |
| `POST`  | `/chats/{chatId}/leave` | Добровольно выйти. Увеличивает epoch для оставшихся. Если уходит CREATOR — применяется каскадная передача (см. 1.3.1). Если группа опустела → удаляется | ЛЮБОЙ |
| `PATCH` | `/chats/{chatId}/members/{userId}/role` | Назначить/снять ADMIN. Body: `{role: "ADMIN"\|"MEMBER"}` | CREATOR |
| `POST`  | `/chats/{chatId}/sender-keys` | Upload batch. Body: `{epoch, entries: [{recipientId, encryptedKey}]}` — загружает свои sender keys для N получателей | ЧЛЕН группы |
| `GET`   | `/chats/{chatId}/sender-keys` | Скачать sender keys где я recipient. Query: `?epoch=e` (optional, иначе latest). Возвращает `[{ownerId, epoch, encryptedKey}]` | ЧЛЕН группы |

### 1.3.1 Каскадная передача CREATOR при выходе

Когда уходит (добровольный `leave` или удаление аккаунта) пользователь с ролью `CREATOR`, права владельца передаются следующему участнику по каскаду:

1. **Из ADMIN'ов** — если в группе остались участники с ролью `ADMIN`,
   случайно выбирается один из них и становится новым `CREATOR`. Его старая
   роль `ADMIN` при этом заменяется на `CREATOR` (не хранится двойная роль).
2. **Из MEMBER'ов** — если `ADMIN`'ов не осталось (все вышли, кикнули или
   изначально не назначались), случайно выбирается любой участник с ролью
   `MEMBER` — он становится `CREATOR`.
3. **Группа удаляется** — если в chat_members не осталось вообще никого
   (последний участник вышел), запись `chats` + все связанные сообщения и
   sender keys удаляются каскадно (`ON DELETE CASCADE`).

Каскад **повторяется рекурсивно**: если новоиспечённый `CREATOR` (бывший
admin или member) тоже сразу выходит, алгоритм запускается заново — снова
ищется случайный `ADMIN`, потом случайный `MEMBER`, и так до опустошения.

Поведение для обычных участников (уходящий = ADMIN или MEMBER, а не
CREATOR) остаётся простым: запись в `chat_members` удаляется, роли
оставшихся не меняются, epoch ротируется чтобы sender keys ушедшего
инвалидировались.

**Почему именно случайный выбор, а не «старейший участник»?**
У случайного выбора нет социальной «несправедливости» — никто не обижается,
что его обошли; при «старейшем» в группах с долгоживущим участником
CREATOR всегда доставался бы одному и тому же человеку. Плюс
криптостойкого рандома у нас и так хватает (`crypto/rand`).

Алгоритм на SQL (внутри одной транзакции, после `DELETE FROM chat_members`):

```sql
-- 1. Проверяем: остался ли кто в группе?
SELECT COUNT(*) FROM chat_members WHERE chat_id = $1;
-- если 0 → DELETE FROM chats WHERE id = $1; COMMIT; return.

-- 2. Нужна ли передача CREATOR'а?
SELECT EXISTS(SELECT 1 FROM chat_members WHERE chat_id = $1 AND role = 'CREATOR');
-- если TRUE (creator есть) → COMMIT; return. Никого не назначаем.

-- 3. Если creator'а нет — ищем случайного ADMIN'а, иначе случайного MEMBER'а.
WITH candidate AS (
    SELECT user_id FROM chat_members
    WHERE chat_id = $1 AND role = 'ADMIN'
    ORDER BY random() LIMIT 1
)
UPDATE chat_members SET role = 'CREATOR'
WHERE chat_id = $1 AND user_id = (SELECT user_id FROM candidate)
RETURNING user_id;
-- Если ADMIN'ов нет → тот же запрос с role = 'MEMBER'.
```

Клиенты получают WS-event `group_role_changed` с новой ролью нового
владельца, плюс `group_member_removed` — на уходящего. Порядок событий
не критичен: оба могут прийти за один reflow UI.

Все идут по существующему SignalingClient. Добавляем типы:

| Тип | Payload | Когда |
|---|---|---|
| `group_created` | `{chatId, title, avatarUrl, createdBy}` | Когда создатель создал группу, участники получают событие с chatId и тянут полный объект через `GET /chats` |
| `group_member_added` | `{chatId, userId, epoch}` | Всем текущим участникам — добавлен новый. Они ждут sender key нового (он сам пришлёт через `group_sender_key_share`) |
| `group_member_removed` | `{chatId, userId, epoch}` | Всем оставшимся — кик/уход. Стартует ротацию ключей |
| `group_info_updated` | `{chatId, title, avatarUrl}` | Меняется название/аватар |
| `group_role_changed` | `{chatId, userId, role}` | Изменилась роль участника |
| `group_sender_key_share` | `{chatId, ownerId, epoch, encryptedKey}` | Прямое сообщение recipient'у — новый sender key (альтернатива REST-у, работает в real-time) |

Offline-очередь (`pending_messages`) должна класть все типы кроме `group_sender_key_share` (он дублируется в REST-store и его не нужно хранить дважды).

### 1.5 Клиентская сторона

**Room миграция** (`AppDatabase` bump версии):
- `ChatEntity`: добавить `role: String = "MEMBER"`, `currentEpoch: Int = 0`.
- Новая таблица `group_sender_keys`:
  ```kotlin
  @Entity(tableName = "group_sender_keys", primaryKeys = ["chatId", "ownerId", "epoch"])
  data class GroupSenderKeyEntity(
      val chatId: String,
      val ownerId: String,  // чей sender key (UUID участника)
      val epoch: Int,
      // расшифрованный sender key (32 байта) в base64 — хранится локально
      // без дополнительного шифрования (локальная БД уже защищена Room +
      // Android Keystore для encrypted DataStore private keys).
      val senderKey: String,
      val createdAt: Long,
  )
  ```

**Crypto — новый класс `GroupCryptoManager`**:
- `generateSenderKey(): ByteArray` — 32 случайных байта
- `wrapSenderKey(senderKey, recipientPublicKey, myPrivate, chatId): ByteArray` — ECDH + HKDF + AES-GCM
- `unwrapSenderKey(encrypted, ownerPublicKey, myPrivate, chatId): ByteArray`
- `encryptGroupMessage(plaintext, senderKey, messageId): ByteArray` — HKDF(senderKey, messageId) + AES-GCM
- `decryptGroupMessage(encrypted, senderKey, messageId): ByteArray`

**Новые Domain entities**:
- `ChatRole { CREATOR, ADMIN, MEMBER }`
- Расширяем `Chat` полями `currentEpoch: Int`, `myRole: ChatRole`.

**Repository**:
- `GroupRepository` (новый интерфейс) или методы в существующем `ChatRepository`:
  ```kotlin
  suspend fun createGroup(title: String, avatarBytes: ByteArray?, memberIds: List<String>): Result<Chat>
  suspend fun addMember(chatId: String, userId: String): Result<Unit>
  suspend fun removeMember(chatId: String, userId: String): Result<Unit>
  suspend fun leaveGroup(chatId: String): Result<Unit>
  suspend fun updateGroupInfo(chatId: String, title: String?, avatarUrl: String?): Result<Unit>
  suspend fun changeRole(chatId: String, userId: String, role: ChatRole): Result<Unit>
  // Sender keys
  suspend fun uploadSenderKeys(chatId: String, epoch: Int, entries: List<SenderKeyEntry>): Result<Unit>
  suspend fun fetchSenderKeys(chatId: String, epoch: Int?): Result<List<SenderKeyEntry>>
  ```

**UI экраны (Compose)**:
1. `ChatListScreen` — существующий. Добавить FAB «+ Новая группа» или пункт в top-bar меню.
2. `CreateGroupScreen`:
   - Поле «Название группы»
   - Кропер аватара (переиспользуем `AvatarCropDialog`)
   - Список контактов + поиск по phone/username (чекбоксы, до 49 контактов)
   - Кнопка «Создать»
3. `GroupInfoScreen` (открывается по тапу на шапку группы в `ChatScreen`):
   - Аватар + название (редактируемо если admin)
   - Список участников с ролями (CREATOR/ADMIN/MEMBER)
   - «Добавить участника» — кнопка, если admin
   - На каждом участнике (если admin): меню «Сделать админом», «Исключить»
   - Кнопка «Выйти из группы»
4. `ChatScreen` — адаптация:
   - Шапка группы: показывает N участников, клик → GroupInfoScreen
   - Отправка: для GROUP используем групповое шифрование (sender key)
   - Приём: ищем sender key по `(chatId, senderId, epoch)`, дешифруем

### 1.6 План реализации по дням

Оценка — относительная, не жёсткие сроки:

| Шаг | Что | Где |
|---|---|---|
| 1 | Миграция `007_groups.sql` + модели Go (Role, SenderKeyEntry, GroupInfo) | Сервер |
| 2 | `GroupSenderKeyRepo` + методы в `ChatRepo` (Role CRUD, Leave, InfoUpdate) | Сервер |
| 3 | Новые REST handlers + маршруты | Сервер |
| 4 | WS events (broadcast при изменениях) | Сервер |
| 5 | Room migration + Entity + Dao | Клиент |
| 6 | `GroupCryptoManager` + unit-тест дешифровки | Клиент |
| 7 | Расширение `ChatRepository` + DI | Клиент |
| 8 | `CreateGroupScreen` + `CreateGroupViewModel` | Клиент |
| 9 | `GroupInfoScreen` + `GroupInfoViewModel` | Клиент |
| 10 | Интеграция отправки/приёма в `ChatViewModel` (ветка GROUP) | Клиент |
| 11 | Тестирование end-to-end на одной машине (2 аккаунта) | QA |
| 12 | CHANGELOG.md → 1.0.68 | |
| 13 | Бамп по команде пользователя | |

### 1.7 Риски и mitigations

| Риск | Mitigation |
|---|---|
| Race в ротации epoch при параллельном add/remove | Серверная транзакция с `SELECT … FOR UPDATE` на `chats.current_epoch` |
| Новый участник не получил sender keys других — не может расшифровать | Участники при получении `group_member_added` авто-отправляют свой sender key новенькому через `group_sender_key_share` |
| Сервер получил сообщение с epoch, которого нет у recipient'а | Клиент ре-fetch-ит sender keys для этого epoch через REST; если нет — ретрай, если всё равно нет — показать «Не удалось расшифровать» (как с direct-keys) |
| Массовый кик/leave → N одновременных ротаций, race | В 1.0.68 одна операция за раз; сервер лочит `chats.id` на время операции |
| Обратная совместимость с клиентами 1.0.67 | У 1.0.67 нет ни UI групп, ни crypto — если кто-то старый получит WS-event `group_created`, его парсер просто логгирует unknown и продолжает работу (уже реализовано в `parseAndEmit`) |

---

## 2. Релиз 1.0.69 — История сообщений для новых участников

Короткий план (детали в своё время):

- При `group_member_added` текущие участники шлют новому не только свой актуальный sender key, но и **все ранее использованные sender keys** (по epoch'ам) — новый юзер сможет расшифровать старые сообщения.
- В передаваемый bundle добавляется `fromEpoch` — минимальный epoch, с которого участник хочет поделиться.
- Пользователь видит в настройках опцию «Ограничить доступ к истории» — можно запретить делиться своими старыми сообщениями.
- Расширение админки: назначение/снятие нескольких ADMIN (не только через CREATOR — можно передавать между админами).

---

## 3. Релиз 1.0.70 — Групповые звонки (SFU)

План:

- Новый Go-сервис `sfu` на базе `pion/ion-sfu` или самописный на `pion/webrtc`.
- Публикует WS-endpoint `/sfu/{callId}` для SDP-обмена.
- Клиент при запуске группового звонка:
  - Публикует свой media stream на SFU
  - Подписывается на streams других участников
  - UI с тайлами участников, mute, speaker detection
- Роли спикер/слушатель (контролируются через право «publish audio»).
- Интеграция с существующим `WebRtcManager` (добавить `GroupCallManager`).

Подробный дизайн напишем когда подойдём к 1.0.70 — сейчас фиксируем только что он будет.

---

## 4. Гарантии обратной совместимости

Все изменения для 1.0.68 удовлетворяют инвариантам:

1. **Клиент 1.0.67 + сервер 1.0.68** — продолжает работать. Старые endpoints не тронуты. Новые WS-типы игнорируются безопасно (`parseAndEmit` логгирует unknown и возвращает).
2. **Клиент 1.0.68 + сервер 1.0.67** — при попытке создать группу получит 404/405, UI покажет ошибку «Обновите приложение». Не падает.
3. **БД миграция 007** применяется через `IF NOT EXISTS` / `ADD COLUMN DEFAULT` — безопасно и идемпотентно, можно откатить просто удалив добавленные объекты (хотя практически не понадобится).
4. **Direct-чаты** не получают никаких изменений. Роль `MEMBER` не читается для direct, флаг `current_epoch = 0` не используется.

---

## 5. Тест-план для 1.0.68

Ручные сценарии перед деплоем:

1. Создание группы 3 участника → все видят сообщения
2. Добавление 4-го → он видит только НОВЫЕ сообщения, не старые
3. Кик 4-го → оставшиеся продолжают общаться, кикнутый отправляет — сервер реджектит
4. Каскадная передача CREATOR:
   - CREATOR уходит при наличии 2 ADMIN'ов → один из них случайно становится CREATOR (запустить 10 раз, проверить что оба хотя бы раз становились)
   - CREATOR уходит без ADMIN'ов (только MEMBER'ы) → случайный MEMBER становится CREATOR
   - Новый CREATOR сразу уходит → каскад повторяется, новый выбирается заново
   - Последний участник уходит → группа удаляется из `chats` + все её сообщения и sender keys по CASCADE
5. Редактирование названия админом → WS-event всем участникам
6. Параллельные add + remove — проверить что sender key ротация не ломается
7. Клиент 1.0.67 в группе 1.0.68 — не появляется группа в списке (ожидаемо, т.к. клиент не умеет GROUP), но direct-чаты работают как раньше

---

*Последнее обновление: перед началом реализации 1.0.68.*
