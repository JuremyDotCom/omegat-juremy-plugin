package org.omegat.machinetranslators.juremy;

import org.omegat.core.machinetranslators.MachineTranslateError;
import org.omegat.util.Language;

public class ConversionHelpers {
    static String languageTo3Char(Language lang) throws Exception {
        switch (lang.getLanguageCode().toUpperCase()) {
                // The 24 EU languages
            case "HU":
                return "hun";
            case "EN":
                return "eng";
            case "DE":
                return "deu";
            case "FR":
                return "fra";
            case "ES":
                return "spa";
            case "IT":
                return "ita";
            case "NL":
                return "nld";
            case "PL":
                return "pol";
            case "PT":
                return "por";
            case "RO":
                return "ron";
            case "SK":
                return "slk";
            case "SL":
                return "slv";
            case "FI":
                return "fin";
            case "SV":
                return "swe";
            case "CS":
                return "ces";
            case "DA":
                return "dan";
            case "ET":
                return "est";
            case "LV":
                return "lav";
            case "LT":
                return "lit";
            case "MT":
                return "mlt";
            case "BG":
                return "bul";
            case "HR":
                return "hrv";
            case "EL":
                return "ell";
            case "GA":
                return "gle";
            default:
                throw new MachineTranslateError(
                        JuremyLookup.BUNDLE.getString("JUREMY_LANGUAGE_NOT_SUPPORTED") + lang.getLanguageCode());
        }
    }
}
