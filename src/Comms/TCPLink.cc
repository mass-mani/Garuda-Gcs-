/****************************************************************************
 *
 * (c) 2009-2024 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/

#include "TCPLink.h"
#include "DeviceInfo.h"
#include "QGCLoggingCategory.h"

#include <QtCore/QThread>
#include <QtCore/QTimer>
#include <QtCore/QRandomGenerator>
#include <QtNetwork/QTcpSocket>

QGC_LOGGING_CATEGORY(TCPLinkLog, "qgc.comms.tcplink")

namespace {
    constexpr int CONNECT_TIMEOUT_MS = 1000;
    constexpr int TYPE_OF_SERVICE = 32; // Set ToS for low delay
}

/*===========================================================================*/

TCPConfiguration::TCPConfiguration(const QString &name, QObject *parent)
    : LinkConfiguration(name, parent)
{
    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;
}

TCPConfiguration::TCPConfiguration(const TCPConfiguration *copy, QObject *parent)
    : LinkConfiguration(copy, parent)
    , _host(copy->host())
    , _port(copy->port())
{
    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;
}

TCPConfiguration::~TCPConfiguration()
{
    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;
}

void TCPConfiguration::copyFrom(const LinkConfiguration *source)
{
    Q_ASSERT(source);
    LinkConfiguration::copyFrom(source);

    const TCPConfiguration* const tcpSource = qobject_cast<const TCPConfiguration*>(source);
    Q_ASSERT(tcpSource);

    setHost(tcpSource->host());
    setPort(tcpSource->port());
}

void TCPConfiguration::loadSettings(QSettings &settings, const QString &root)
{
    settings.beginGroup(root);

    setHost(settings.value(QStringLiteral("host"), host()).toString());
    setPort(static_cast<quint16>(settings.value(QStringLiteral("port"), port()).toUInt()));

    settings.endGroup();
}

void TCPConfiguration::saveSettings(QSettings &settings, const QString &root) const
{
    settings.beginGroup(root);

    settings.setValue(QStringLiteral("host"), host());
    settings.setValue(QStringLiteral("port"), port());

    settings.endGroup();
}

/*===========================================================================*/

TCPWorker::TCPWorker(const TCPConfiguration *config, QObject *parent)
    : QObject(parent)
    , _config(config)
{
    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;
}

TCPWorker::~TCPWorker()
{
    disconnectFromHost();

    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;
}

bool TCPWorker::isConnected() const
{
    return (_socket && _socket->isOpen() && (_socket->state() == QAbstractSocket::ConnectedState));
}

void TCPWorker::setupSocket()
{
    Q_ASSERT(!_socket);
    _socket = new QTcpSocket(this);
    _reconnectTimer = new QTimer(this);
    _reconnectTimer->setSingleShot(true);
    (void) connect(_reconnectTimer, &QTimer::timeout, this, &TCPWorker::_attemptReconnect);

    _socket->setSocketOption(QAbstractSocket::LowDelayOption, 1);
    _socket->setSocketOption(QAbstractSocket::KeepAliveOption, 1);
    _socket->setSocketOption(QAbstractSocket::TypeOfServiceOption, TYPE_OF_SERVICE);

    (void) connect(_socket, &QTcpSocket::connected, this, &TCPWorker::_onSocketConnected);
    (void) connect(_socket, &QTcpSocket::disconnected, this, &TCPWorker::_onSocketDisconnected);
    (void) connect(_socket, &QTcpSocket::readyRead, this, &TCPWorker::_onSocketReadyRead);
    (void) connect(_socket, &QTcpSocket::errorOccurred, this, &TCPWorker::_onSocketErrorOccurred);

    if (TCPLinkLog().isDebugEnabled()) {
        // (void) connect(_socket, &QTcpSocket::bytesWritten, this, &TCPWorker::_onSocketBytesWritten);

        (void) connect(_socket, &QTcpSocket::stateChanged, this, [](QTcpSocket::SocketState state) {
            qCDebug(TCPLinkLog) << "TCP State Changed:" << state;
        });

        (void) connect(_socket, &QTcpSocket::hostFound, this, []() {
            qCDebug(TCPLinkLog) << "TCP Host Found";
        });
    }
}

void TCPWorker::connectToHost()
{
    _connectionRequested = true;
    _disconnectNotified = false;
    _reconnectDelayMs = 1000;
    _attemptReconnect();
}

void TCPWorker::disconnectFromHost()
{
    _connectionRequested = false;
    _reconnectTimer->stop();
    if (isConnected()) {
        _socket->disconnectFromHost();
    } else if (!_disconnectNotified) {
        _disconnectNotified = true;
        emit disconnected();
    }
}

void TCPWorker::_attemptReconnect()
{
    if (!_connectionRequested || isConnected()) {
        return;
    }

    _reconnectTimer->stop();
    qCInfo(TCPLinkLog) << "Connecting to" << _config->host() << ":" << _config->port();
    _socket->abort();
    _socket->connectToHost(_config->host(), _config->port());

    if (!_socket->waitForConnected(CONNECT_TIMEOUT_MS)) {
        qCWarning(TCPLinkLog) << "Connection to" << _config->host() << ":" << _config->port() << "failed:" << _socket->errorString();
        _socket->abort();
        _scheduleReconnect();
    }
}

void TCPWorker::_scheduleReconnect()
{
    if (!_connectionRequested || _reconnectTimer->isActive()) {
        return;
    }
    const int delayMs = _reconnectDelayMs + QRandomGenerator::global()->bounded(250);
    qCInfo(TCPLinkLog) << "Retrying TCP link in" << delayMs << "ms";
    _reconnectTimer->start(delayMs);
    _reconnectDelayMs = qMin(_reconnectDelayMs * 2, 30000);
}

void TCPWorker::writeData(const QByteArray &data)
{
    if (data.isEmpty()) {
        emit errorOccurred(tr("Data to Send is Empty"));
        return;
    }

    if (!isConnected()) {
        emit errorOccurred(tr("Socket is not connected"));
        return;
    }

    qint64 totalBytesWritten = 0;
    while (totalBytesWritten < data.size()) {
        const qint64 bytesWritten = _socket->write(data.constData() + totalBytesWritten, data.size() - totalBytesWritten);
        if (bytesWritten == -1) {
            emit errorOccurred(tr("Could Not Send Data - Write Failed: %1").arg(_socket->errorString()));
            return;
        } else if (bytesWritten == 0) {
            emit errorOccurred(tr("Could Not Send Data - Write Returned 0 Bytes"));
            return;
        }
        totalBytesWritten += bytesWritten;
    }

    emit dataSent(data.first(totalBytesWritten));
}

void TCPWorker::_onSocketConnected()
{
    qCDebug(TCPLinkLog) << "Socket connected:" << _config->host() << _config->port();
    _errorEmitted = false;
    _connectedOnce = true;
    _disconnectNotified = false;
    _reconnectTimer->stop();
    emit connected();
}

void TCPWorker::_onSocketDisconnected()
{
    qCDebug(TCPLinkLog) << "Socket disconnected:" << _config->host() << _config->port();
    if (_connectionRequested) {
        _scheduleReconnect();
    } else if (!_disconnectNotified) {
        _disconnectNotified = true;
        emit disconnected();
    }
}

void TCPWorker::_onSocketReadyRead()
{
    const QByteArray data = _socket->readAll();
    if (!data.isEmpty()) {
        _reconnectDelayMs = 1000;
    }
    emit dataReceived(data);
}

void TCPWorker::_onSocketBytesWritten(qint64 bytes)
{
    qCDebug(TCPLinkLog) << _config->host() << "Wrote" << bytes << "bytes";
}

void TCPWorker::_onSocketErrorOccurred(QAbstractSocket::SocketError socketError)
{
    const QString errorString = _socket->errorString();

    qCWarning(TCPLinkLog) << "Socket error:" << errorString;

    // Peer-closed telemetry sockets are recoverable. Keep the configured link
    // alive and retry quietly instead of showing a modal error for each drop.
    if (socketError != QAbstractSocket::RemoteHostClosedError && !_errorEmitted && !_connectedOnce) {
        emit errorOccurred(errorString);
        _errorEmitted = true;
    }
}

/*===========================================================================*/

TCPLink::TCPLink(SharedLinkConfigurationPtr &config, QObject *parent)
    : LinkInterface(config, parent)
    , _tcpConfig(qobject_cast<const TCPConfiguration*>(config.get()))
    , _worker(new TCPWorker(_tcpConfig))
    , _workerThread(new QThread(this))
{
    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;

    _workerThread->setObjectName(QStringLiteral("TCP_%1").arg(_tcpConfig->name()));

    _worker->moveToThread(_workerThread);

    (void) connect(_workerThread, &QThread::started, _worker, &TCPWorker::setupSocket);
    (void) connect(_workerThread, &QThread::finished, _worker, &QObject::deleteLater);

    (void) connect(_worker, &TCPWorker::connected, this, &TCPLink::_onConnected, Qt::QueuedConnection);
    (void) connect(_worker, &TCPWorker::disconnected, this, &TCPLink::_onDisconnected, Qt::QueuedConnection);
    (void) connect(_worker, &TCPWorker::errorOccurred, this, &TCPLink::_onErrorOccurred, Qt::QueuedConnection);
    (void) connect(_worker, &TCPWorker::dataReceived, this, &TCPLink::_onDataReceived, Qt::QueuedConnection);
    (void) connect(_worker, &TCPWorker::dataSent, this, &TCPLink::_onDataSent, Qt::QueuedConnection);

    _workerThread->start();
}

TCPLink::~TCPLink()
{
    TCPLink::disconnect();

    _workerThread->quit();
    if (!_workerThread->wait()) {
        qCWarning(TCPLinkLog) << "Failed to wait for TCP Thread to close";
    }

    // qCDebug(TCPLinkLog) << Q_FUNC_INFO << this;
}

bool TCPLink::isConnected() const
{
    return _worker->isConnected();
}

bool TCPLink::_connect()
{
    return QMetaObject::invokeMethod(_worker, "connectToHost", Qt::QueuedConnection);
}

void TCPLink::disconnect()
{
    (void) QMetaObject::invokeMethod(_worker, "disconnectFromHost", Qt::QueuedConnection);
}

void TCPLink::_onConnected()
{
    emit connected();
}

void TCPLink::_onDisconnected()
{
    emit disconnected();
}

void TCPLink::_onErrorOccurred(const QString &errorString)
{
    qCWarning(TCPLinkLog) << "Communication error:" << errorString;
    emit communicationError(tr("TCP Link Error"), tr("Link %1: (Host: %2 Port: %3) %4").arg(_tcpConfig->name(), _tcpConfig->host()).arg(_tcpConfig->port()).arg(errorString));
}

void TCPLink::_onDataReceived(const QByteArray &data)
{
    emit bytesReceived(this, data);
}

void TCPLink::_onDataSent(const QByteArray &data)
{
    emit bytesSent(this, data);
}

void TCPLink::_writeBytes(const QByteArray& bytes)
{
    (void) QMetaObject::invokeMethod(_worker, "writeData", Qt::QueuedConnection, Q_ARG(QByteArray, bytes));
}

bool TCPLink::isSecureConnection() const
{
    return QGCDeviceInfo::isNetworkEthernet();
}
