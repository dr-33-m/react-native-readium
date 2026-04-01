import AVFoundation
import Combine
import Foundation
import ReadiumNavigator
import ReadiumShared

@MainActor
class TTSManager: PublicationSpeechSynthesizerDelegate {

    private var synthesizer: PublicationSpeechSynthesizer?
    private let publication: Publication
    private var currentRate: Float = 1.0

    var onStateChange: ((Bool, Bool, Float) -> Void)?
    var onUtterance: ((ReadiumShared.Locator, String) -> Void)?
    var onError: ((String) -> Void)?

    init(publication: Publication) {
        self.publication = publication
    }

    func start(rate: Float?, language: String?, voice: String?, fromLocator: ReadiumShared.Locator?) {
        if let rate = rate {
            currentRate = min(max(rate, 0.25), 4.0)
        }

        if synthesizer != nil {
            synthesizer?.resume()
            return
        }

        guard PublicationSpeechSynthesizer.canSpeak(publication: publication) else {
            onError?("TTS is not available for this publication")
            return
        }

        var config = PublicationSpeechSynthesizer.Configuration()
        // Prefer explicit language, otherwise derive from the voice identifier
        if let lang = language {
            config.defaultLanguage = Language(stringLiteral: lang)
        } else if let voiceId = voice,
                  let avVoice = AVSpeechSynthesisVoice(identifier: voiceId) {
            config.defaultLanguage = Language(stringLiteral: avVoice.language)
        }

        guard let synth = PublicationSpeechSynthesizer(
            publication: publication,
            config: config
        ) else {
            onError?("Failed to create TTS synthesizer")
            return
        }

        synth.delegate = self
        synthesizer = synth

        if let locator = fromLocator {
            synth.start(from: locator)
        } else {
            synth.start()
        }
    }

    func pause() {
        synthesizer?.pause()
    }

    func resume() {
        synthesizer?.resume()
    }

    func stop() {
        synthesizer?.stop()
        synthesizer = nil
        onStateChange?(false, false, currentRate)
    }

    func setRate(_ rate: Float) {
        currentRate = min(max(rate, 0.25), 4.0)
        // Rate change takes effect on next utterance
    }

    func skipNext() {
        synthesizer?.next()
    }

    func skipPrevious() {
        synthesizer?.previous()
    }

    func cleanup() {
        stop()
    }

    // MARK: - PublicationSpeechSynthesizerDelegate

    func publicationSpeechSynthesizer(
        _ synthesizer: PublicationSpeechSynthesizer,
        stateDidChange state: PublicationSpeechSynthesizer.State
    ) {
        switch state {
        case .stopped:
            onStateChange?(false, false, currentRate)
        case .paused(let utterance):
            onStateChange?(false, true, currentRate)
            onUtterance?(utterance.locator, utterance.text)
        case .playing(let utterance, let range):
            onStateChange?(true, false, currentRate)
            let loc = range ?? utterance.locator
            onUtterance?(loc, utterance.text)
        }
    }

    func publicationSpeechSynthesizer(
        _ synthesizer: PublicationSpeechSynthesizer,
        utterance: PublicationSpeechSynthesizer.Utterance,
        didFailWithError error: PublicationSpeechSynthesizer.Error
    ) {
        onError?("TTS error: \(error.localizedDescription)")
    }
}
