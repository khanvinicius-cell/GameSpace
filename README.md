# VykaR Game Space + VykaT — projeto-fonte integrado

Esta pasta reúne os projetos fornecidos de VykaR e VykaT em um único módulo Android (`:app`), mantendo o `applicationId` original do VykaR (`dev.vyka.rl`). O objetivo é permitir que o usuário compile o APK localmente.

## O que foi integrado nesta versão

- A implementação do serviço de sobreposição/tradução do VykaT e suas preferências foram incorporadas ao pacote Kotlin do VykaR.
- As dependências ML Kit e Google Tasks do tradutor foram adicionadas ao Gradle do aplicativo principal.
- Manifesto consolidado com permissão de sobreposição, Internet, provedor Shizuku e serviço de tradução.
- A atividade de configurações do tradutor foi renomeada para `TranslateSettingsActivity` para não colidir com a `MainActivity` do VykaR.
- A tela inicial do VykaR recebeu um botão de acesso às configurações do VykaT.
- A categoria `HOME` foi removida do manifesto: o VykaR deixa de se registrar como launcher padrão do Android e continua disponível pela gaveta de aplicativos.
- O botão de acesso rápido do tradutor mantém a ação de iniciar/parar o serviço de sobreposição.

## Abrir e compilar

1. Extraia o ZIP.
2. Abra a pasta `VykaR` no Android Studio.
3. Use JDK 17 e permita que o Gradle sincronize as dependências.
4. Compile pelo menu **Build > Build APK(s)**.
5. O APK de depuração normalmente aparece em `app/build/outputs/apk/debug/app-debug.apk`.

Não há `gradlew` incluído nos ZIPs de origem. Se o Android Studio pedir para atualizar ferramentas, prefira primeiro manter as versões declaradas no projeto e só atualizar se necessário.

## Verificações importantes antes de usar no aparelho

Esta entrega é uma **base de integração de código-fonte**, não um APK validado. O ambiente desta sessão não tem uma cadeia Android/Gradle disponível para executar uma compilação real. Portanto, a primeira compilação deve ser tratada como teste e erros de compilação precisam ser corrigidos antes de instalar.

Além disso, as escolhas visuais e funcionais discutidas foram registradas como especificação, mas ainda não estão todas implementadas neste pacote. Em particular, ainda requerem desenvolvimento/testes: redesenho integral da interface Game Space AMOLED com navegação inferior e biblioteca em grade; seleção de papel de parede e cor de destaque; editor de perfis leve/equilibrado/agressivo; políticas de limpeza por jogo; gestos configuráveis do botão do tradutor e menu radial; seletor de cor/tamanho/opacidade do botão; restauração emergencial persistente; detecção robusta de encerramento do jogo e reversão de todos os ajustes; integração e validação das ações de desempenho disponíveis no Android/Shizuku.

## Notas técnicas e limitações

- O serviço de tradução usa permissões de sobreposição e o motor ML Kit; o acesso à captura de tela pode ser bloqueado por aplicativos protegidos ou por políticas do Android.
- A compilação ART, a taxa de atualização, o modo de desempenho e a gestão de processos dependem da versão do Android, fabricante e permissões concedidas. Não são garantidos em todos os dispositivos.
- Encerrar aplicativos de terceiros não garante que eles permaneçam encerrados: Android e fabricantes podem reiniciá-los.
- A remoção da categoria `HOME` evita que o VykaR se ofereça como launcher padrão. A atividade de launcher continua registrada com a categoria `LAUNCHER`, permitindo abrir o Game Space normalmente.
- Não instale sobre a versão existente sem considerar que a assinatura do APK de depuração precisa corresponder à instalação anterior. Faça backup das configurações importantes.

## Estrutura relevante

- `app/src/main/java/dev/resshortcut/app/` — código VykaR e componentes incorporados do VykaT.
- `app/src/main/AndroidManifest.xml` — permissões e componentes consolidados.
- `app/build.gradle.kts` — SDK, Kotlin e dependências.
