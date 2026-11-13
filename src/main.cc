/****************************************************************************
 *
 * (c) 2009-2024 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/

#include <QtQuick/QQuickWindow>
#include <QtWidgets/QApplication>
#ifdef GARUDA_UAV_LOG_VIEWER
#include <QtWebEngineQuick/qtwebenginequickglobal.h>
#endif

#ifdef Q_OS_MACOS
    #include <QtCore/QProcessEnvironment>
#endif

#include "QGCApplication.h"
#include "QGCLogging.h"
#include "CmdLineOptParser.h"
#include "SettingsManager.h"
#include "MavlinkSettings.h"

#if !defined(Q_OS_ANDROID) && !defined(Q_OS_IOS)
    #include <QtWidgets/QMessageBox>
    #include <QtWidgets/QSplashScreen>
    #include <QtGui/QScreen>
    #include <QtGui/QPainter>
    #include <QtGui/QPainterPath>
    #include <QtCore/QPropertyAnimation>
    #include <QtCore/QElapsedTimer>
    #include <QtCore/QTimer>
    #include <memory>
    #include "RunGuard.h"
#endif

#ifdef Q_OS_ANDROID
    #include "AndroidInterface.h"
#endif

#ifdef Q_OS_LINUX
#ifndef Q_OS_ANDROID
    #include "SignalHandler.h"
#endif
#endif

#ifdef QT_DEBUG
#ifdef QGC_UNITTEST_BUILD
    #include "UnitTestList.h"
#endif

#ifdef Q_OS_WIN

#include <crtdbg.h>
#include <windows.h>
#include <iostream>

/// @brief CRT Report Hook installed using _CrtSetReportHook. We install this hook when
/// we don't want asserts to pop a dialog on windows.
int WindowsCrtReportHook(int reportType, char* message, int* returnValue)
{
    Q_UNUSED(reportType);

    std::cerr << message << std::endl;  // Output message to stderr
    *returnValue = 0;                   // Don't break into debugger
    return true;                        // We handled this fully ourselves
}

#endif // Q_OS_WIN

#endif // QT_DEBUG

//-----------------------------------------------------------------------------
/**
 * @brief Starts the application
 *
 * @param argc Number of commandline arguments
 * @param argv Commandline arguments
 * @return exit code, 0 for normal exit and !=0 for error cases
 */

int main(int argc, char *argv[])
{
    bool runUnitTests = false;
    bool simpleBootTest = false;
    QString systemIdStr = QString();
    bool hasSystemId = false;
    bool bypassRunGuard = false;

    bool stressUnitTests = false;       // Stress test unit tests
    bool quietWindowsAsserts = false;   // Don't let asserts pop dialog boxes
    QString unitTestOptions;

    CmdLineOpt_t rgCmdLineOptions[] = {
#ifdef QT_DEBUG
        { "--unittest",             &runUnitTests,          &unitTestOptions },
        { "--unittest-stress",      &stressUnitTests,       &unitTestOptions },
        { "--no-windows-assert-ui", &quietWindowsAsserts,   nullptr },
        { "--allow-multiple",       &bypassRunGuard,        nullptr },
#endif
        { "--system-id",            &hasSystemId,           &systemIdStr },
        { "--simple-boot-test",     &simpleBootTest,        nullptr },
        // Add additional command line option flags here
    };

    ParseCmdLineOptions(argc, argv, rgCmdLineOptions, std::size(rgCmdLineOptions), false);

#if !defined(Q_OS_ANDROID) && !defined(Q_OS_IOS)
    // We make the runguard key different for custom and non custom
    // builds, so they can be executed together in the same device.
    // Stable and Daily have same QGC_APP_NAME so they would
    // not be able to run at the same time
    const QString runguardString = QStringLiteral("%1 RunGuardKey").arg(QGC_APP_NAME);

    RunGuard guard(runguardString);
    if (!simpleBootTest && !bypassRunGuard && !guard.tryToRun()) {
        QApplication errorApp(argc, argv);
        QMessageBox::critical(nullptr, QObject::tr("Error"),
            QObject::tr("A second instance of %1 is already running. Please close the other instance and try again.").arg(QGC_APP_NAME)
        );
        return -1;
    }
#endif

#ifdef Q_OS_LINUX
#ifndef Q_OS_ANDROID
    if (getuid() == 0) {
        QApplication errorApp(argc, argv);
        QMessageBox::critical(nullptr, QObject::tr("Error"),
            QObject::tr("You are running %1 as root. "
                "You should not do this since it will cause other issues with %1."
                "%1 will now exit.<br/><br/>").arg(QGC_APP_NAME)
        );
        return -1;
    }
#endif
#endif

#ifdef Q_OS_UNIX
    if (!qEnvironmentVariableIsSet("QT_LOGGING_TO_CONSOLE")) {
        qputenv("QT_LOGGING_TO_CONSOLE", "1");
    }
#endif

    QGCLogging::installHandler();

#ifdef Q_OS_MACOS
    // Prevent Apple's app nap from screwing us over
    // tip: the domain can be cross-checked on the command line with <defaults domains>
    QProcess::execute("defaults", {"write org.qgroundcontrol.qgroundcontrol NSAppSleepDisabled -bool YES"});
#endif

#ifdef Q_OS_WIN
    // Set our own OpenGL buglist
    // qputenv("QT_OPENGL_BUGLIST", ":/opengl/resources/opengl/buglist.json");

    // Allow for command line override of renderer
    for (int i = 0; i < argc; i++) {
        const QString arg(argv[i]);
        if (arg == QStringLiteral("-desktop")) {
            QCoreApplication::setAttribute(Qt::AA_UseDesktopOpenGL);
            break;
        } else if (arg == QStringLiteral("-swrast")) {
            QCoreApplication::setAttribute(Qt::AA_UseSoftwareOpenGL);
            break;
        }
    }
#endif

#ifdef QT_DEBUG
    if (stressUnitTests) {
        runUnitTests = true;
    }

#ifdef Q_OS_WIN
    if (!qEnvironmentVariableIsSet("QT_WIN_DEBUG_CONSOLE")) {
        qputenv("QT_WIN_DEBUG_CONSOLE", "attach"); // new
    }

    if (quietWindowsAsserts) {
        _CrtSetReportHook(WindowsCrtReportHook);
    }

    if (runUnitTests) {
        // Don't pop up Windows Error Reporting dialog when app crashes. This prevents TeamCity from
        // hanging.
        const DWORD dwMode = SetErrorMode(SEM_NOGPFAULTERRORBOX);
        SetErrorMode(dwMode | SEM_NOGPFAULTERRORBOX);
    }
#endif // Q_OS_WIN
#endif // QT_DEBUG

#ifdef GARUDA_UAV_LOG_VIEWER
    QCoreApplication::setAttribute(Qt::AA_ShareOpenGLContexts);
    QtWebEngineQuick::initialize();
#endif
    QGCApplication app(argc, argv, runUnitTests, simpleBootTest);

#if !defined(Q_OS_ANDROID) && !defined(Q_OS_IOS)
    std::unique_ptr<QSplashScreen> splash;
    QElapsedTimer splashElapsed;
    if (!runUnitTests && !simpleBootTest) {
        QPixmap artwork(QStringLiteral(":/res/GarudaSplash.png"));
        if (!artwork.isNull()) {
            const QScreen *screen = app.primaryScreen();
            const QSize bounds = screen ? screen->availableGeometry().size() * 0.75 : QSize(960, 720);
            artwork = artwork.scaled(bounds.boundedTo(QSize(960, 720)), Qt::KeepAspectRatio, Qt::SmoothTransformation);
            QPixmap roundedArtwork(artwork.size());
            roundedArtwork.fill(Qt::transparent);
            {
                QPainter painter(&roundedArtwork);
                painter.setRenderHint(QPainter::Antialiasing);
                painter.setRenderHint(QPainter::SmoothPixmapTransform);
                QPainterPath clipPath;
                clipPath.addRoundedRect(QRectF(roundedArtwork.rect()), 24.0, 24.0);
                painter.setClipPath(clipPath);
                painter.drawPixmap(0, 0, artwork);
            }
            splash = std::make_unique<QSplashScreen>(roundedArtwork, Qt::FramelessWindowHint | Qt::WindowStaysOnTopHint);
            splash->setAttribute(Qt::WA_TranslucentBackground);
            splash->setMask(roundedArtwork.mask());
            splash->setWindowTitle(QObject::tr("Starting Garuda GCS"));
            splash->setWindowOpacity(0.0);
            splash->show();
            splashElapsed.start();
            splash->showMessage(QObject::tr("Starting Garuda GCS…"), Qt::AlignBottom | Qt::AlignHCenter, Qt::white);
            auto *fadeIn = new QPropertyAnimation(splash.get(), "windowOpacity", splash.get());
            fadeIn->setDuration(500);
            fadeIn->setStartValue(0.0);
            fadeIn->setEndValue(1.0);
            fadeIn->start(QAbstractAnimation::DeleteWhenStopped);
            app.processEvents();
        }
    }
#endif

#ifdef Q_OS_LINUX
#ifndef Q_OS_ANDROID
    SignalHandler::instance();
    (void) SignalHandler::setupSignalHandlers();
#endif
#endif

    app.init();

#if !defined(Q_OS_ANDROID) && !defined(Q_OS_IOS)
    if (splash) {
        if (QQuickWindow *window = app.mainRootWindow()) {
            const auto dismissSplash = [splashWidget = splash.get(), splashElapsed]() {
                if (splashWidget->property("fadeOutScheduled").toBool()) {
                    return;
                }
                splashWidget->setProperty("fadeOutScheduled", true);
                const qint64 remaining = 1600 - splashElapsed.elapsed();
                const int delayMs = remaining > 0 ? static_cast<int>(remaining) : 0;
                QTimer::singleShot(delayMs, splashWidget, [splashWidget]() {
                    auto *fadeOut = new QPropertyAnimation(splashWidget, "windowOpacity", splashWidget);
                    fadeOut->setDuration(350);
                    fadeOut->setStartValue(splashWidget->windowOpacity());
                    fadeOut->setEndValue(0.0);
                    QObject::connect(fadeOut, &QPropertyAnimation::finished, splashWidget, &QWidget::close);
                    fadeOut->start(QAbstractAnimation::DeleteWhenStopped);
                });
            };
            QObject::connect(window, &QQuickWindow::frameSwapped, splash.get(), dismissSplash,
                             Qt::ConnectionType(Qt::QueuedConnection | Qt::SingleShotConnection));
            window->requestUpdate();
            // Also dismiss if the main window starts minimized and does not render a frame.
            QTimer::singleShot(15000, splash.get(), dismissSplash);
        } else {
            splash->close();
        }
    }
#endif

    // Set system ID if specified via command line, for example --system-id:255
    if (hasSystemId) {
        bool ok;
        int systemId = systemIdStr.toInt(&ok);
        if (ok && systemId >= 1 && systemId <= 255) {  // MAVLink system IDs are 8-bit
            qDebug() << "Setting MAVLink System ID to:" << systemId;
            SettingsManager::instance()->mavlinkSettings()->gcsMavlinkSystemID()->setRawValue(systemId);
        } else {
            qDebug() << "Not setting MAVLink System ID. It must be between 0 and 255. Invalid system ID value:" << systemIdStr;
        }
    }

    int exitCode = 0;

#ifdef QGC_UNITTEST_BUILD
    if (runUnitTests) {
        exitCode = runTests(stressUnitTests, unitTestOptions);
    } else
#endif
    {
        #ifdef Q_OS_ANDROID
            AndroidInterface::checkStoragePermissions();
        #endif

        if (!simpleBootTest) {
            exitCode = app.exec();
        }
    }

    app.shutdown();

    qDebug() << "Exiting main";

    return exitCode;
}
