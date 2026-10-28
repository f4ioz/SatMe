/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */

// The installer must never delete the user's files: jpackage's WiX fragment
// (bundle.wxf, written by jdk.jpackage's WixAppImageFragmentBuilder, not a
// template) has one component whose <util:RemoveFolderEx On="uninstall">
// empties the whole install folder when the product is uninstalled — and a
// major upgrade uninstalls the version before. This takes that component out
// (its ComponentRef and the Property its RegistrySearch fills too), nothing
// else, and fails when the fragment is not the one expected (a JDK change).
//
//   java pc/windows/SansNettoyage.java bundle.wxf sortie.wxf
//
// Run by pc/windows/paquet-windows.sh between its two MSI passes.

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class SansNettoyage {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) echec("usage : java SansNettoyage.java bundle.wxf sortie.wxf");
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        Document d = f.newDocumentBuilder().parse(new File(args[0]));

        // The one RemoveFolderEx, in a component of its own.
        List<Element> rm = elements(d, "RemoveFolderEx");
        if (rm.size() != 1) echec(rm.size() + " RemoveFolderEx dans " + args[0] + " (1 attendu) : le gabarit de jpackage a changé, vérifier le nettoyage du dossier d'installation");
        Element r = rm.get(0);
        String propriete = r.getAttribute("Property");
        Element composant = parent(r, "Component");
        String id = composant.getAttribute("Id");
        if (propriete.isEmpty() || id.isEmpty()) echec("RemoveFolderEx sans Property ou composant sans Id");
        for (Element e : enfants(composant))
            if (!Set.of("RegistryKey", "RemoveFolderEx").contains(e.getLocalName()))
                echec("le composant " + id + " contient aussi <" + e.getLocalName() + "> : pas seulement le nettoyage, rien retiré");
        Element dossier = parent(composant, "DirectoryRef");
        dossier.removeChild(composant);
        if (enfants(dossier).isEmpty()) dossier.getParentNode().removeChild(dossier);

        // Its reference in the « Files » group, and the property read from the registry at uninstall.
        retireUn(d, "ComponentRef", id);
        Element p = retireUn(d, "Property", propriete);
        for (Element e : enfants(p))
            if (!e.getLocalName().equals("RegistrySearch")) echec("la propriété " + propriete + " contient <" + e.getLocalName() + ">");

        var t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var sortie = new File(args[1]);
        t.transform(new DOMSource(d), new StreamResult(sortie));
        String texte = Files.readString(sortie.toPath(), StandardCharsets.UTF_8);
        if (texte.contains("RemoveFolderEx") || texte.contains("\"" + id + "\"") || texte.contains("\"" + propriete + "\""))
            echec("nettoyage encore présent dans " + args[1]);
        System.out.println("bundle.wxf : composant " + id + " (RemoveFolderEx, propriété " + propriete + ") retiré");
    }

    private static Element retireUn(Document d, String nom, String id) {
        List<Element> l = new ArrayList<>();
        for (Element e : elements(d, nom)) if (id.equals(e.getAttribute("Id"))) l.add(e);
        if (l.size() != 1) echec(l.size() + " <" + nom + " Id=\"" + id + "\"> (1 attendu)");
        Element e = l.get(0);
        e.getParentNode().removeChild(e);
        return e;
    }

    private static Element parent(Element e, String nom) {
        Node n = e.getParentNode();
        if (!(n instanceof Element p) || !nom.equals(p.getLocalName())) echec("<" + e.getLocalName() + "> hors d'un <" + nom + ">");
        return (Element) n;
    }

    private static List<Element> elements(Document d, String nom) {
        NodeList l = d.getElementsByTagNameNS("*", nom);
        List<Element> r = new ArrayList<>();
        for (int i = 0; i < l.getLength(); i++) r.add((Element) l.item(i));
        return r;
    }

    private static List<Element> enfants(Element e) {
        List<Element> r = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) r.add(c);
        return r;
    }

    private static void echec(String raison) {
        System.err.println("SansNettoyage : " + raison);
        System.exit(1);
    }
}
