package com.dancingcat.beat;

/**
 * Ritim kaynağı. Kedi her karede buna "şu an kaçıncı vuruştayız?" diye sorar.
 * Şimdilik sabit tempo var; ileride ses analizi yapan bir uygulaması gelecek.
 */
public interface BeatSource {

    /** Başlangıçtan beri geçen vuruş sayısı (ör. 3.5 = dördüncü vuruşun ortası). */
    double beatPosition(long nowMillis);
}
