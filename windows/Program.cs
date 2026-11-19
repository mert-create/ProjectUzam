using System.IO;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading.Channels;
using System.Windows;
using System.Windows.Controls;

namespace Nexora.Host;

internal static class Program
{
    [STAThread]
    private static void Main() => new Application().Run(new HostWindow());
}

internal sealed class HostWindow : Window
{
    private const int Port = 47832;
    private const byte ShortcutProtocol = 19;
    private static readonly HashSet<string> BrowserProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "firefox", "brave", "opera", "opera_gx", "vivaldi",
        "chromium", "waterfox", "librewolf", "zen", "thorium", "floorp", "arc", "iexplore"
    };
    private static readonly (byte Modifier, byte Key)[] KeyboardShortcuts =
    {
        (0x11, 0x5A), (0x11, 0x59), (0x11, 0x58), (0x11, 0x43), (0x11, 0x56), (0x11, 0x41),
        (0x11, 0x53), (0x11, 0x46), (0x11, 0x50), (0x11, 0x54), (0x12, 0x09), (0x5B, 0x44)
    };
    private readonly TextBlock status = new() { TextWrapping = TextWrapping.Wrap, Margin = new(0, 16, 0, 12) };
    private CancellationTokenSource? session;

    public HostWindow()
    {
        Title = "Uzam";
        Icon = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/mert.ico"));
        Width = 480;
        Height = 280;
        var panel = new StackPanel { Margin = new(24) };
        panel.Children.Add(new TextBlock { Text = "Telefonu bağla", FontSize = 24 });
        panel.Children.Add(status);
        var toggle = new Button { Content = "Dinlemeyi başlat", Padding = new(12, 6, 12, 6) };
        panel.Children.Add(toggle);
        var usb = new Button { Content = "USB’yi hazırla", Padding = new(12, 6, 12, 6), Margin = new(0, 8, 0, 0) };
        var usbStatus = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new(0, 6, 0, 0) };
        panel.Children.Add(usb);
        panel.Children.Add(usbStatus);
        Height = 340;
        usb.Click += async (_, _) =>
        {
            usb.IsEnabled = false;
            usbStatus.Text = "USB kontrol ediliyor…";
            try
            {
                await PrepareUsb();
                if (session is null)
                    toggle.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                usbStatus.Text = session is not null
                    ? "USB hazır. Telefonda USB’ye basın."
                    : "USB tüneli hazır. Dinleyici durumunu yukarıdan kontrol edin.";
            }
            catch (Exception error) { usbStatus.Text = error.Message; }
            finally { usb.IsEnabled = true; }
        };
        Content = panel;
        status.Text = "Bağlantı kapalı.";
        Closed += (_, _) => session?.Cancel();
        toggle.Click += async (_, _) =>
        {
            if (session is not null)
            {
                session.Cancel();
                toggle.IsEnabled = false;
                return;
            }

            using var lifetime = new CancellationTokenSource();
            session = lifetime;
            toggle.Content = "Dinlemeyi durdur";
            try { await Listen(lifetime.Token); }
            catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
            { status.Text = "Bağlantı kapalı."; }
            catch (SocketException error) when (error.SocketErrorCode == SocketError.AddressAlreadyInUse)
            { status.Text = $"Port {Port} kullanımda. Önce açık olan diğer Uzam penceresini kapatıp tekrar deneyin."; }
            catch (Exception error) { status.Text = error.Message; }
            finally
            {
                session = null;
                toggle.Content = "Dinlemeyi başlat";
                toggle.IsEnabled = true;
            }
        };
    }

    private static async Task PrepareUsb()
    {
        var sdkRoots = new[]
        {
            Environment.GetEnvironmentVariable("ANDROID_HOME"),
            Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk")
        };
        var adb = sdkRoots.Where(root => !string.IsNullOrWhiteSpace(root))
            .Select(root => Path.Combine(root!, "platform-tools", "adb.exe"))
            .FirstOrDefault(File.Exists) ?? "adb.exe";
        var start = new ProcessStartInfo(adb)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true
        };
        foreach (var argument in new[] { "-d", "reverse", $"tcp:{Port}", $"tcp:{Port}" })
            start.ArgumentList.Add(argument);

        Process process;
        try { process = Process.Start(start)!; }
        catch (System.ComponentModel.Win32Exception error) when (error.NativeErrorCode is 2 or 3)
        { throw new InvalidOperationException("ADB bulunamadı. Android SDK Platform-Tools’u kurun.", error); }
        using (process)
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15)))
        {
            var stderr = process.StandardError.ReadToEndAsync(timeout.Token);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
                var detail = (await stderr).Trim();
                if (process.ExitCode == 0) return;
                if (detail.Contains("unauthorized", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("Telefonun kilidini açıp USB hata ayıklama iznini onaylayın.");
                throw new InvalidOperationException($"USB hazırlanamadı. Veri kablosunu ve USB hata ayıklamayı kontrol edin. {detail}");
            }
            catch (OperationCanceledException)
            {
                try { process.Kill(); } catch (InvalidOperationException) { }
                throw new TimeoutException("ADB yanıt vermedi. Telefonun bağlantısını kontrol edip tekrar deneyin.");
            }
        }
    }

    private async Task Listen(CancellationToken cancellation)
    {
        var addresses = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address));
        var endpoint = $"Telefonun adres alanına girin: {string.Join(", ", addresses)} (port {Port})";
        var listener = new TcpListener(IPAddress.Any, Port);
        listener.Start(4);
        try
        {
            while (true)
            {
                status.Text = endpoint;
                using var client = await listener.AcceptTcpClientAsync(cancellation);
                try { await Control(client, cancellation); }
                catch (Exception error) when (error is IOException or SocketException or System.Security.Authentication.AuthenticationException or OperationCanceledException)
                {
                    cancellation.ThrowIfCancellationRequested();
                    status.Text = "Bağlantı sona erdi.";
                    await Task.Delay(700, cancellation);
                }
            }
        }
        finally { listener.Stop(); }
    }

    private async Task Control(TcpClient client, CancellationToken cancellation)
    {
        client.NoDelay = true;
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=Uzam", key, HashAlgorithmName.SHA256);
        using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddHours(1));
        using var cert = ImportServerCertificate(generated);
        using var tls = new SslStream(client.GetStream());
        using var pairing = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        pairing.CancelAfter(TimeSpan.FromSeconds(60));
        await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = cert }, pairing.Token);

        var buffer = new byte[3];
        await tls.ReadExactlyAsync(buffer.AsMemory(0, 1), pairing.Token);
        if (buffer[0] is >= 2 and <= ShortcutProtocol)
        {
            await tls.WriteAsync(buffer.AsMemory(0, 1), pairing.Token);
        }
        else if (buffer[0] != 1)
        {
            return;
        }

        var code = Convert.ToHexString(SHA256.HashData(cert.RawData).AsSpan(0, 12));
        var approved = MessageBox.Show(this,
            $"Telefondaki kodla karşılaştırın:\n\n{code}\n\nKod aynıysa ve bağlantıyı siz başlattıysanız onaylayın.",
            "Telefon eşleştirme", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.Yes)
            == MessageBoxResult.Yes;
        pairing.Token.ThrowIfCancellationRequested();
        await tls.WriteAsync(new byte[] { approved ? (byte)1 : (byte)0 }, pairing.Token);
        if (!approved) return;

        status.Text = $"Telefon bağlı · {client.Client.RemoteEndPoint}";
        bool dragging = false;
        using var brightnessLifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        var brightness = Channel.CreateBounded<(int Delta, int? Level)>(16);
        var brightnessWorker = RunBrightnessQueue(brightness.Reader, brightnessLifetime.Token);
        try
        {
            while (true)
            {
                await tls.ReadExactlyAsync(buffer, cancellation);
                cancellation.ThrowIfCancellationRequested();
                switch (buffer[0])
                {
                    case 1:
                        mouse_event(1, (sbyte)buffer[1], (sbyte)buffer[2], 0, UIntPtr.Zero);
                        break;
                    case 2:
                        Click(2, 4);
                        break;
                    case 3:
                        Click(8, 16);
                        break;
                    case 4:
                        mouse_event(0x0800, 0, 0, unchecked((uint)((sbyte)buffer[1] * 120)), UIntPtr.Zero);
                        break;
                    case 5:
                        PressControlTab((sbyte)buffer[1] < 0 ? (byte)0x10 : (byte)0);
                        break;
                    case 6:
                        PressControlTab(0x12); // Ctrl+Alt+Tab keeps the app switcher open.
                        break;
                    case 7:
                        PressShortcut(0x5B, 0, 0x44);
                        break;
                    case 8:
                        OpenTarget("https://web.whatsapp.com/");
                        break;
                    case 9:
                        OpenTarget("shell:Downloads");
                        break;
                    case 10:
                        OpenTarget("https://www.youtube.com/");
                        break;
                    case 11:
                        PressShortcut(0, 0, 0xAD);
                        break;
                    case 12:
                        PressShortcut(0, 0, 0xB3);
                        break;
                    case 13:
                        if (!LockWorkStation()) status.Text = "Windows oturumu kilitlenemedi.";
                        break;
                    case 14:
                    case 18:
                    case 22:
                        int length = (buffer[1] << 8) | buffer[2];
                        if (length is 0 or > 2048) return;
                        string value = await ReadTextPayload(tls, length, cancellation);
                        if (buffer[0] == 14) OpenWebShortcut(value);
                        else TypeText(value, submit: buffer[0] == 22);
                        break;
                    case 15:
                        CloseForegroundTabOrWindow();
                        break;
                    case 23:
                        PressShortcut(0x12, 0, 0x73);
                        break;
                    case 24:
                        PressShortcut(0x12, 0, 0x25);
                        break;
                    case 25:
                        PressShortcut(0, 0, 0x46);
                        break;
                    case 26:
                        PressShortcut(0x5B, 0x10, 0x53);
                        break;
                    case 27:
                        PressShortcut(0, 0, 0xAE);
                        break;
                    case 28:
                        PressShortcut(0, 0, 0xAF);
                        break;
                    case 29 or 30:
                        brightness.Writer.TryWrite((buffer[0] == 30 ? 10 : -10, null));
                        break;
                    case 33:
                        PressShortcut(0x12, 0, 0x27);
                        break;
                    case 34:
                        PressShortcut(0x5B, 0x11, 0x56);
                        break;
                    case 36:
                        PressShortcut(0x5B, 0, 0x26);
                        break;
                    case 37:
                        PressShortcut(0, 0, 0x74);
                        break;
                    case 38:
                        PressShortcut(0x11, 0x10, 0x54);
                        break;
                    case 35:
                        bool volumeUp = (sbyte)buffer[1] > 0;
                        if (IsWindowSwitcherActive())
                            PressShortcut(0, 0, volumeUp ? (byte)0x27 : (byte)0x25);
                        else
                            PressControlTab(volumeUp ? (byte)0x10 : (byte)0);
                        break;
                    case 32:
                        PressShortcut(0x11, 0, 0x54);
                        break;
                    case 40:
                        await SendBrowserLink(tls, cancellation);
                        break;
                    case 39 when buffer[1] <= 100:
                        brightness.Writer.TryWrite((0, buffer[1]));
                        break;
                    case 31:
                        PressShortcut(0, 0, 0x1B);
                        break;
                    case 16:
                        await RequestShutdown(cancellation);
                        break;
                    case 17 when buffer[1] < KeyboardShortcuts.Length:
                        var (modifier, virtualKey) = KeyboardShortcuts[buffer[1]];
                        PressShortcut(modifier, 0, virtualKey);
                        break;
                    case 19 when !dragging:
                        mouse_event(2, 0, 0, 0, UIntPtr.Zero);
                        dragging = true;
                        break;
                    case 20 when dragging:
                        mouse_event(4, 0, 0, 0, UIntPtr.Zero);
                        dragging = false;
                        break;
                    case 19 or 20:
                        break;
                    case 21:
                        Click(2, 4);
                        Click(2, 4);
                        break;
                    default:
                        return;
                }
            }
        }
        finally
        {
            if (dragging) mouse_event(4, 0, 0, 0, UIntPtr.Zero);
            brightnessLifetime.Cancel();
            await brightnessWorker;
        }
    }

    private static X509Certificate2 ImportServerCertificate(X509Certificate2 generated)
    {
        // Schannel needs a user key container for the server's private key.
        // Without PersistKeySet, disposing the imported certificate deletes the container.
        var pfx = generated.Export(X509ContentType.Pfx);
        try
        {
            return new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.UserKeySet);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(pfx);
        }
    }

    private static void Click(uint down, uint up)
    {
        mouse_event(down, 0, 0, 0, UIntPtr.Zero);
        mouse_event(up, 0, 0, 0, UIntPtr.Zero);
    }

    private static async Task<string> ReadTextPayload(SslStream tls, int length, CancellationToken cancellation)
    {
        var payload = new byte[length];
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        await tls.ReadExactlyAsync(payload, timeout.Token);
        return Encoding.UTF8.GetString(payload);
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    private async Task SendBrowserLink(SslStream tls, CancellationToken cancellation)
    {
        string address = "";
        var window = GetForegroundWindow();
        if (GetWindowProcessName(window) is { } name && BrowserProcesses.Contains(name))
        {
            try
            {
                var previous = System.Windows.Clipboard.GetDataObject();
                uint before = GetClipboardSequenceNumber();
                PressShortcut(0x11, 0, 0x4C);
                await Task.Delay(100, cancellation);
                if (window == GetForegroundWindow())
                {
                    PressShortcut(0x11, 0, 0x43);
                    for (int attempt = 0; attempt < 10 && GetClipboardSequenceNumber() == before; attempt++)
                        await Task.Delay(30);
                    uint copied = GetClipboardSequenceNumber();
                    if (copied != before && window == GetForegroundWindow())
                    {
                        try { address = System.Windows.Clipboard.GetText().Trim(); }
                        finally
                        {
                            if (GetClipboardSequenceNumber() == copied)
                            {
                                if (previous is null) System.Windows.Clipboard.Clear();
                                else System.Windows.Clipboard.SetDataObject(previous, true);
                            }
                        }
                    }
                    if (window == GetForegroundWindow()) PressShortcut(0, 0, 0x1B);
                }
            }
            catch (System.Runtime.InteropServices.ExternalException) { address = ""; }
        }

        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri)
            || uri.Scheme is not ("http" or "https") || uri.Host.Length == 0 || uri.UserInfo.Length != 0)
            address = "";

        byte[] payload = System.Text.Encoding.UTF8.GetBytes(address);
        if (payload.Length > 2048) payload = Array.Empty<byte>();
        await tls.WriteAsync(new byte[] { 40, (byte)(payload.Length >> 8), (byte)payload.Length }, cancellation);
        await tls.WriteAsync(payload, cancellation);
    }

    private void OpenWebShortcut(string address)
    {
        if (Uri.TryCreate(address, UriKind.Absolute, out var uri)
            && uri.Scheme is "https" or "http"
            && uri.Host.Length > 0 && uri.UserInfo.Length == 0)
        {
            OpenTarget(uri.AbsoluteUri);
        }
    }

    private void TypeText(string text, bool submit)
    {
        const uint Unicode = 4, KeyUp = 2;
        var inputs = new NativeInput[text.Length * 2 + (submit ? 2 : 0)];
        for (int index = 0; index < text.Length; index++)
        {
            inputs[index * 2] = UnicodeInput(text[index], Unicode);
            inputs[index * 2 + 1] = UnicodeInput(text[index], Unicode | KeyUp);
        }
        if (submit)
        {
            inputs[^2] = new NativeInput { Type = 1, Data = new InputData { Keyboard = new KeyboardInput { VirtualKey = 0x0D } } };
            inputs[^1] = new NativeInput { Type = 1, Data = new InputData { Keyboard = new KeyboardInput { VirtualKey = 0x0D, Flags = KeyUp } } };
        }

        if (SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<NativeInput>()) != inputs.Length)
            status.Text = "Metin odaktaki uygulamaya gönderilemedi.";
    }

    private static NativeInput UnicodeInput(char character, uint flags) => new()
    {
        Type = 1,
        Data = new InputData { Keyboard = new KeyboardInput { Scan = character, Flags = flags } }
    };

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeInput
    {
        public uint Type;
        public InputData Data;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputData
    {
        [FieldOffset(0)] public KeyboardInput Keyboard;
        // Keep the union the same size as Windows INPUT on x86 and x64.
        [FieldOffset(0)] public MouseInput Mouse;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort VirtualKey, Scan;
        public uint Flags, Time;
        public UIntPtr ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int X, Y;
        public uint Data, Flags, Time;
        public UIntPtr ExtraInfo;
    }

    [DllImport("user32.dll")]
    private static extern uint SendInput(uint count, [In] NativeInput[] inputs, int size);

    private async Task RunBrightnessQueue(ChannelReader<(int Delta, int? Level)> reader, CancellationToken cancellation)
    {
        try
        {
            await foreach (var request in reader.ReadAllAsync(cancellation))
                await AdjustBrightness(request.Delta, request.Level, cancellation);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
    }

    private async Task AdjustBrightness(int delta, int? level, CancellationToken cancellation)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe"))
        {
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var argument in new[] { "-NoProfile", "-NonInteractive", "-Command" })
            start.ArgumentList.Add(argument);

        string target = level?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? $"[int]$monitor.CurrentBrightness + ({delta})";
        start.ArgumentList.Add($$"""
            $ErrorActionPreference = 'Stop'
            $monitor = Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness |
                Where-Object Active | Select-Object -First 1
            if ($null -eq $monitor) { exit 2 }
            $method = Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods |
                Where-Object { $_.InstanceName -eq $monitor.InstanceName } | Select-Object -First 1
            if ($null -eq $method) { exit 2 }
            $target = [Math]::Max(0, [Math]::Min(100, {{target}}))
            $level = $monitor.Level | Sort-Object { [Math]::Abs([int]$_ - $target) } | Select-Object -First 1
            if ({{delta}} -ne 0 -and $level -eq $monitor.CurrentBrightness) {
                $level = $monitor.Level | Where-Object { ([int]$_ - [int]$monitor.CurrentBrightness) * ({{delta}}) -gt 0 } |
                    Sort-Object { [Math]::Abs([int]$_ - $target) } | Select-Object -First 1
            }
            if ($null -eq $level) { exit 0 }
            $result = Invoke-CimMethod -InputObject $method -MethodName WmiSetBrightness -Arguments @{
                Timeout = [uint32]0
                Brightness = [byte]$level
            }
            if ($result.ReturnValue -ne 0) { exit 1 }
            """);
        try
        {
            using var process = Process.Start(start);
            if (process is not null)
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                timeout.CancelAfter(TimeSpan.FromSeconds(6));
                try { await process.WaitForExitAsync(timeout.Token); }
                catch (OperationCanceledException)
                {
                    try { process.Kill(); }
                    catch (InvalidOperationException) { }
                    catch (System.ComponentModel.Win32Exception) { }
                    cancellation.ThrowIfCancellationRequested();
                    status.Text = "Ekran parlaklığı işlemi zamanında tamamlanmadı.";
                    return;
                }
                if (process.ExitCode == 0) return;
            }
        }
        catch (System.ComponentModel.Win32Exception) { }
        status.Text = "Windows bu ekranın parlaklığını değiştiremedi.";
    }

    private async Task RequestShutdown(CancellationToken cancellation)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "shutdown.exe"))
        {
            UseShellExecute = false,
            CreateNoWindow = true
        };
        start.ArgumentList.Add("/s");
        start.ArgumentList.Add("/t");
        start.ArgumentList.Add("0");
        try
        {
            using var process = Process.Start(start);
            if (process is not null)
            {
                await process.WaitForExitAsync(cancellation);
                if (process.ExitCode == 0) return;
            }
        }
        catch (System.ComponentModel.Win32Exception)
        {
            status.Text = "Windows kapatma komutunu başlatamadı.";
            return;
        }
        status.Text = "Windows kapatma komutunu kabul etmedi.";
    }

    private void OpenTarget(string target)
    {
        try
        {
            using var browser = Process.Start(new ProcessStartInfo(target)
            {
                UseShellExecute = true
            });
        }
        catch (System.ComponentModel.Win32Exception)
        {
            status.Text = "Kısayol Windows’ta açılamadı.";
        }
    }

    private void CloseForegroundTabOrWindow()
    {
        var window = GetForegroundWindow();
        if (window == IntPtr.Zero || window == GetShellWindow()) return;

        var processName = GetWindowProcessName(window);
        if (window != GetForegroundWindow()) return;
        if (processName is not null && BrowserProcesses.Contains(processName))
        {
            PressShortcut(0x11, 0, 0x57);
        }
        else if (!PostMessage(window, 0x0010, IntPtr.Zero, IntPtr.Zero))
        {
            status.Text = "Öndeki pencereye kapatma isteği gönderilemedi.";
        }
    }

    private static string? GetWindowProcessName(IntPtr window)
    {
        GetWindowThreadProcessId(window, out uint processId);
        if (processId == 0) return null;
        try
        {
            using var process = Process.GetProcessById((int)processId);
            return process.ProcessName;
        }
        catch (ArgumentException) { return null; }
        catch (InvalidOperationException) { return null; }
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern IntPtr GetShellWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll", EntryPoint = "PostMessageW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    private static bool IsWindowSwitcherActive()
    {
        var className = new System.Text.StringBuilder(128);
        GetClassName(GetForegroundWindow(), className, className.Capacity);
        return className.ToString() is "MultitaskingViewFrame" or "TaskSwitcherWnd";
    }

    [System.Runtime.InteropServices.DllImport("user32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern int GetClassName(IntPtr window, System.Text.StringBuilder className, int capacity);

    private static void PressControlTab(byte modifier) => PressShortcut(0x11, modifier, 0x09);

    private static void PressShortcut(byte primaryModifier, byte modifier, byte key)
    {
        const uint KeyUp = 2;
        if (primaryModifier != 0) keybd_event(primaryModifier, 0, 0, UIntPtr.Zero);
        if (modifier != 0) keybd_event(modifier, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, KeyUp, UIntPtr.Zero);
        if (modifier != 0) keybd_event(modifier, 0, KeyUp, UIntPtr.Zero);
        if (primaryModifier != 0) keybd_event(primaryModifier, 0, KeyUp, UIntPtr.Zero);
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LockWorkStation();

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extraInfo);

    [DllImport("user32.dll")]
    private static extern void mouse_event(uint flags, int dx, int dy, uint data, UIntPtr extraInfo);
}

