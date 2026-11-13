#include "AdditionalVideoSource.h"
#include "VideoReceiver.h"
#include "VideoManager.h"
#include "QGCCorePlugin.h"
#include "SettingsManager.h"
#include "AppSettings.h"
#include "QGCApplication.h"
#include "UVCReceiver.h"
#include "MultiVehicleManager.h"
#include "Vehicle.h"
#include <QQuickWindow>
#include <QQuickItem>
#include <QDateTime>
#include <QDir>
#include <QRandomGenerator>

namespace {
QQuickItem *findVideoSurface(QQuickItem *item, const QString &name)
{
    if (!item) return nullptr;
    if (item->objectName() == name) return item;
    for (QQuickItem *child : item->childItems()) {
        if (auto *result = findVideoSurface(child, name)) return result;
    }
    return nullptr;
}
}

AdditionalVideoSource::AdditionalVideoSource(int number, QObject *parent)
    : QObject(parent), _settings(new VideoSettings(this, QStringLiteral("Video%1").arg(number))), _number(number)
{
    _retry.setSingleShot(true);
    connect(&_retry, &QTimer::timeout, this, &AdditionalVideoSource::start);
    for (Fact *fact : {_settings->videoSource(), _settings->rtspUrl(), _settings->tcpUrl(),
                       _settings->udpUrl(), _settings->streamEnabled(), _settings->lowLatencyMode(), _settings->rtspTimeout(),
                       _settings->rtspTransport(), _settings->rtspLatencyMs()}) {
        connect(fact, &Fact::rawValueChanged, this, [this]() {
            _retryAttempt = 0;
            reconnect();
        });
    }
    connect(MultiVehicleManager::instance(), &MultiVehicleManager::activeVehicleChanged, this, [this](Vehicle *vehicle) {
        disconnect(_armedConnection);
        if (vehicle) {
            _armedConnection = connect(vehicle, &Vehicle::armedChanged, this, [this](bool armed) {
                if (!armed && _recording && _settings->disableWhenDisarmed()->rawValue().toBool()) {
                    _receiver->stopRecording();
                }
            });
        }
    });
}

bool AdditionalVideoSource::configured() const
{
    return _settings->streamEnabled()->rawValue().toBool() && _settings->streamConfigured();
}

bool AdditionalVideoSource::isUvc() const
{
    return UVCReceiver::enabled() && UVCReceiver::deviceExists(_settings->videoSource()->rawValue().toString());
}

QString AdditionalVideoSource::uri() const
{
    const QString source = _settings->videoSource()->rawValue().toString();
    if (source == VideoSettings::videoSourceRTSP) return _settings->rtspUrl()->rawValue().toString().trimmed();
    if (source == VideoSettings::videoSourceTCP) return QStringLiteral("tcp://") + _settings->tcpUrl()->rawValue().toString().trimmed();
    if (source == VideoSettings::videoSourceUDPH264) return QStringLiteral("udp://") + _settings->udpUrl()->rawValue().toString().trimmed();
    if (source == VideoSettings::videoSourceUDPH265) return QStringLiteral("udp265://") + _settings->udpUrl()->rawValue().toString().trimmed();
    if (source == VideoSettings::videoSourceMPEGTS) return QStringLiteral("mpegts://") + _settings->udpUrl()->rawValue().toString().trimmed();
    if (source == VideoSettings::videoSource3DRSolo) return QStringLiteral("udp://0.0.0.0:5600");
    if (source == VideoSettings::videoSourceParrotDiscovery) return QStringLiteral("udp://0.0.0.0:8888");
    if (source == VideoSettings::videoSourceYuneecMantisG) return QStringLiteral("rtsp://192.168.42.1:554/live");
    if (source == VideoSettings::videoSourceHerelinkAirUnit) return QStringLiteral("rtsp://192.168.0.10:8554/H264Video");
    if (source == VideoSettings::videoSourceHerelinkHotspot) return QStringLiteral("rtsp://192.168.43.1:8554/fpv_stream");
    return {};
}

void AdditionalVideoSource::setStatus(const QString &status)
{
    _status = status;
    emit stateChanged();
}

void AdditionalVideoSource::init(QQuickWindow *window)
{
    if (_receiver) return;
    _receiver = QGCCorePlugin::instance()->createVideoReceiver(this);
    if (!_receiver) { setStatus(tr("Video backend unavailable")); return; }
    _receiver->setName(QStringLiteral("videoContent%1").arg(_number));
    _receiver->setWidget(findVideoSurface(window->contentItem(), _receiver->name()));
    if (!_receiver->widget()) { setStatus(tr("Video surface unavailable")); return; }
    _receiver->setSink(QGCCorePlugin::instance()->createVideoSink(_receiver->widget(), _receiver));
    connect(_receiver, &VideoReceiver::onStartComplete, this, [this](VideoReceiver::STATUS status) {
        _starting = false;
        if (status == VideoReceiver::STATUS_OK) {
            _receiver->setStarted(true);
            if (_closing || _reconnectPending || !configured() || _receiver->uri() != uri()) { reconnect(); return; }
            _receiver->startDecoding(_receiver->sink());
        } else {
            setStatus(tr("Connection failed"));
            scheduleRetry();
        }
    });
    connect(_receiver, &VideoReceiver::onStopComplete, this, [this](VideoReceiver::STATUS) {
        _receiver->setStarted(false);
        _stopping = false;
        _decoding = false;
        setStatus(tr("Waiting for video"));
        scheduleRetry();
    });
    connect(_receiver, &VideoReceiver::decodingChanged, this, [this](bool active) {
        _decoding = active;
        if (active) _retryAttempt = 0;
        setStatus(active ? tr("Live") : tr("Waiting for video"));
    });
    connect(_receiver, &VideoReceiver::recordingChanged, this, [this](bool active) {
        _recording = active;
        emit stateChanged();
    });
    connect(_receiver, &VideoReceiver::onStartRecordingComplete, this, [this](VideoReceiver::STATUS status) {
        if (status != VideoReceiver::STATUS_OK) qgcApp()->showAppMessage(tr("Source %1: recording could not start.").arg(_number));
    });
    // Start on the GUI event loop after the render surfaces have been initialized.
    _retry.start(0);
}

void AdditionalVideoSource::start()
{
    if (_closing || !_receiver || _starting || _stopping || _receiver->started() || !configured() || isUvc()) return;
    if (!_receiver->sink()) { setStatus(tr("Video surface unavailable")); return; }
    _receiver->setUri(uri());
    if (_receiver->uri().isEmpty()) return;
    _receiver->setLowLatency(_settings->lowLatencyMode()->rawValue().toBool());
    _receiver->setRtspTransport(_settings->rtspTransport()->rawValue().toInt());
    _receiver->setRtspLatencyMs(_settings->rtspLatencyMs()->rawValue().toUInt());
    _reconnectPending = false;
    _starting = true;
    setStatus(tr("Connecting…"));
    _receiver->start(_settings->rtspTimeout()->rawValue().toUInt());
}

void AdditionalVideoSource::reconnect()
{
    _retryAttempt = 0;
    _retry.stop();
    _reconnectPending = true;
    emit stateChanged();
    if (!_receiver) return;
    if (_receiver->started() && !_stopping) {
        _stopping = true;
        _receiver->stop();
    } else if (!_starting && !_stopping && !_closing) {
        _retry.start(0);
    }
}

void AdditionalVideoSource::scheduleRetry()
{
    if (_closing || !configured()) return;
    const int exponent = qMin(_retryAttempt, 5);
    const int baseDelayMs = qMin(30000, 1000 << exponent);
    const int delayMs = qMin(30000, baseDelayMs + static_cast<int>(QRandomGenerator::global()->bounded(250u)));
    ++_retryAttempt;
    setStatus(tr("Retry %1 in %2 s").arg(_retryAttempt).arg((delayMs + 999) / 1000));
    _retry.start(delayMs);
}

void AdditionalVideoSource::cleanup()
{
    _closing = true;
    _retry.stop();
    if (!_receiver) return;
    disconnect(_receiver, nullptr, this, nullptr);
    // Receiver destruction drains its worker before releasing the render sink.
    void *sink = _receiver->sink();
    delete _receiver;
    _receiver = nullptr;
    QGCCorePlugin::instance()->releaseVideoSink(sink);
}

void AdditionalVideoSource::toggleRecording()
{
    if (!_receiver || !_decoding) return;
    if (_recording) { _receiver->stopRecording(); return; }
    const auto format = static_cast<VideoReceiver::FILE_FORMAT>(_settings->recordingFormat()->rawValue().toInt());
    if (!VideoReceiver::isValidFileFormat(format)) return;
    VideoManager::_cleanupOldVideos();
    const QString folder = SettingsManager::instance()->appSettings()->videoSavePath();
    if (folder.isEmpty() || !QDir().mkpath(folder)) {
        qgcApp()->showAppMessage(tr("Please select a writable video save folder in Settings."));
        return;
    }
    static const char *extensions[] = {"mkv", "mov", "mp4"};
    const QString file = QDir(folder).filePath(QDateTime::currentDateTime().toString("yyyy-MM-dd_hh.mm.ss.zzz")
        + QStringLiteral(".source%1.%2").arg(_number).arg(extensions[format]));
    _receiver->startRecording(file, format);
}

QString AdditionalVideoSource::snapshotPath() const
{
    const QString folder = SettingsManager::instance()->appSettings()->photoSavePath();
    if (folder.isEmpty() || !QDir().mkpath(folder)) return {};
    return QDir(folder).filePath(QDateTime::currentDateTime().toString("yyyy-MM-dd_hh.mm.ss.zzz")
        + QStringLiteral(".source%1.jpg").arg(_number));
}
