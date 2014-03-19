package br.com.paskuale.jazzpell;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

public class Main {

	public static void main(String[] args) throws Exception {
		String input = "house";
		InputStream affix = new FileInputStream(new File("/usr/share/hunspell/en_US.aff"));
		InputStream dictionary = new FileInputStream(new File("/usr/share/hunspell/en_US.dic"));
		HunspellDictionary dict = new HunspellDictionary(affix, dictionary);
		List<HunspellWord> words = dict.lookupWord(input.toCharArray(), 0, 5);
		if (words == null) {
			System.out.println("Misspelling? " + input);
		} else {
			for (HunspellWord word : words) {
				System.out.println(new String(word.getFlags()));
			}
		}
		affix.close();
		dictionary.close();
	}
	
}
