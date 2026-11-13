#pragma once

#include <QObject>
#include <QTimer>
#include "VideoSettings.h"

class VideoReceiver;
class QQuickWindow;
class Vehicle;

// A manually configured source, independent of the vehicle's primary/thermal cameras.
class AdditionalVideoSource : public QObject
{
    Q_OBJECT
    Q_PROPERTY(VideoSettings* settings READ settings CONSTANT)
    Q_PROPERTY(int number READ number CONSTANT)
    Q_PROPERTY(bool configured READ configured NOTIFY stateChanged)
    Q_PROPERTY(bool isUvc READ isUvc NOTIFY stateChanged)
    Q_PROPERTY(bool decoding READ decoding NOTIFY stateChanged)
    Q_PROPERTY(bool recording READ recording NOTIFY stateChanged)
    Q_PROPERTY(QString status READ status NOTIFY stateChanged)
public:
    AdditionalVideoSource(int number, QObject *parent);
    VideoSettings *settings() const { return _settings; }
    int number() const { return _number; }
    bool configured() const;
    bool isUvc() const;
    bool decoding() const { return _decoding; }
    bool recording() const { return _recording; }
    QString status() const { return _status; }
    void init(QQuickWindow *window);
    void cleanup();
    Q_INVOKABLE void reconnect();
    Q_INVOKABLE void toggleRecording();
    Q_INVOKABLE QString snapshotPath() const;
signals:
    void stateChanged();
private:
    QString uri() const;
    void start();
    void setStatus(const QString &status);
    void scheduleRetry();
    VideoSettings *_settings;
    VideoReceiver *_receiver = nullptr;
    const int _number;
    QTimer _retry;
    bool _starting = false;
    bool _reconnectPending = false;
    bool _stopping = false;
    bool _closing = false;
    int _retryAttempt = 0;
    bool _decoding = false;
    bool _recording = false;
    QString _status;
    QMetaObject::Connection _armedConnection;
};
