# WoodSeller

NPC Citizens penjual **Papan Kayu** (item Oraxen `papan_kayu`) dengan dialog dan GUI jual.

- Target: Paper 1.21.11, Java 21
- Wajib terpasang di server: Citizens, Oraxen, Vault + satu plugin ekonomi (mis. EssentialsX)

## Build jar lewat GitHub
1. Upload seluruh isi folder ini ke repository GitHub (branch `main`).
2. Buka tab **Actions** -> workflow **Build WoodSeller** (otomatis jalan saat push, atau klik *Run workflow*).
3. Setelah hijau, buka run tersebut -> bagian **Artifacts** -> unduh `WoodSeller-1.0.0` (isinya `WoodSeller-1.0.0.jar`).
4. Taruh jar di folder `plugins/` server, lalu restart.

## Build lokal
```
mvn clean package
```
Hasil: `target/WoodSeller-1.0.0.jar`

Jika muncul "Could not find artifact" untuk Citizens/Oraxen, ubah versi di bagian `<properties>` pada `pom.xml`
(cek versi tersedia di https://maven.citizensnpcs.co/#/repo/net/citizensnpcs/citizens-main).

## Pemakaian
1. `/npc create Pak_Tukang_Kayu`, lalu `/npc id` untuk melihat id.
2. `/woodseller npc add <id>`
3. Klik kanan NPC -> dialog -> GUI jual (atur jumlah dengan -/+ lalu jual, atau jual semua).

## Command & permission
| Command | Permission |
|---|---|
| `/woodseller npc <add\|remove\|list> [id]` | `woodseller.admin` (default op) |
| `/woodseller reload` | `woodseller.admin` |
| berdagang dengan NPC | `woodseller.use` (default semua pemain) |

Pengaturan harga, teks, dan material tombol ada di `plugins/WoodSeller/config.yml`.
