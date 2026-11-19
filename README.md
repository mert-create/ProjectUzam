# Uzam

Uzam, Android telefonu Windows bilgisayar için kablosuz veya USB bağlantılı bir touchpad, klavye ve kısayol kumandasına dönüştürür. İki cihaz yerel olarak haberleşir ve bulut hesabı gerekmez.

Uzam, benim ilk vibe coding deneyimim. Projeyi yapay zekâyla birlikte geliştirirken özellikleri deneye deneye şekillendirdim.

## Özellikler

- Yatay touchpad, sol ve sağ tıklama, çift tıklama, sürükleme ve iki parmakla kaydırma
- Windows’a metin gönderme ve tarayıcı, pencere ve medya kısayolları
- Android’den **Paylaş → Uzam** ile bir web sayfasını bilgisayarda açma
- Bilgisayarda desteklenen bir tarayıcı ön plandayken **Linki Al** ile açık sayfayı telefona getirme
- Desteklenen ekranlarda yüzdeyle parlaklık ayarı ve Windows 11’de ses çıkışı seçimi
- Android ana ekran widget’ından **Masaüstü**, **Kilit** ve **PC’yi Kapat** komutları

## Kurulum

Android APK’sını telefona kurun ve Windows paketindeki **Nexora.Host.exe** dosyasını çalıştırın. Host’u paketindeki diğer dosyalarla aynı klasörde tutun. APK ve Host’u aynı sürümden kullanın.

### Wi-Fi ile bağlanma

1. Telefonu ve bilgisayarı aynı yerel ağa bağlayın.
2. Windows Host’ta **Dinlemeyi başlat** düğmesine basın. Windows Güvenlik Duvarı izin isterse güvendiğiniz özel ağ için izin verin.
3. Telefonda Uzam’i açın. **Bağlantı** alanına Host ekranındaki IPv4 adresini girip **Bağlan**’a dokunun. TCP portu uygulamada **47832** olarak ayarlıdır.
4. İki ekrandaki eşleştirme kodunun tamamı aynıysa telefonda **Kod aynı**, bilgisayarda **Evet** seçin.

### USB ile bağlanma

1. Windows’ta Android SDK Platform-Tools kurulu olsun. Telefonda **Geliştirici seçenekleri → USB hata ayıklama** ayarını açın.
2. Telefonu veri aktarabilen bir USB kablosuyla bağlayın ve telefon sorarsa bu bilgisayara hata ayıklama izni verin.
3. Host’ta **USB’yi hazırla** düğmesine basın. ADB tüneli kurulunca Host otomatik olarak dinlemeye başlar.
4. Telefonda **Bağlantı → USB** seçeneğine dokunun ve eşleştirme kodunu iki ekranda karşılaştırıp onaylayın.

USB bağlantısı için Wi-Fi veya IP girişi gerekmez. Kabloyu çıkarıp yeniden taktığınızda **USB’yi hazırla** adımını tekrarlayın.

## Kullanım

- **Touchpad** — Tek parmakla bir kez dokunmak sol tık, iki parmakla bir kez dokunmak sağ tık yapar. Tek parmakla iki kez dokunup bırakmak çift tık yapar. Sürüklemek veya metin seçmek için ikinci dokunuşta parmağı kaldırmadan hareket ettirin.
- **Kısayollar** — İki düğmeye sırayla basılı tutarak yerlerini değiştirin. Yeni sıra telefonda saklanır.
- **Bağlantı paylaşımı** — Telefonda bir HTTP veya HTTPS bağlantısını paylaşırken **Uzam**’i seçin. Ters yönde, bilgisayarda tarayıcı ön plandayken **Linki Al → Telefonda Aç** yolunu kullanın.
- **Widget** — Android ana ekranında boş bir alana basılı tutup **Widget’lar → Uzam** üzerinden ekleyin. Komutlar açık bağlantıyı kullanır. **PC’yi Kapat** komutunu onaylamak için düğmeye beş saniye içinde tekrar dokunun.

## Bağlantı güvenliği

Bağlantı TLS ile şifrelenir. Her yeni oturumda iki ekrandaki kodu karşılaştırıp bağlantıyı onaylayın. Host aynı anda bir telefon kabul eder. Yalnızca başlattığınız eşleştirme isteğini onaylayın ve TCP 47832 portunu internete yönlendirmeyin.

## Kaynak koddan derleme

Windows için .NET 8 SDK gerekir. Android için Android Studio veya JDK 17 uyumlu bir ortam ve Android SDK Platform 37 gerekir. Android uygulaması API 26 ve üzeri Android cihazlara kurulabilir.

Proje kökünde PowerShell’den çalıştırın:

```powershell
dotnet publish .\windows\Nexora.Host.csproj -c Release -o .\windows\bin\Uzam-release
.\android\gradlew.bat -p android assembleDebug
```

- Windows paketi `windows/bin/Uzam-release/` klasörüne çıkar. Çalıştırmak için .NET 8 Desktop Runtime gerekir.
- Android APK’sı `android/app/build/outputs/apk/debug/app-debug.apk` yoluna çıkar. Android Studio kullanıyorsanız proje olarak `android/` klasörünü açın.

## Geliştirme durumu

Gerçek ikinci ekran, çift yönlü dosya aktarımı ve telefondan biyometriyle Windows kilidini açma geliştirme kapsamındadır.

