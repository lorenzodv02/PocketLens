#include "pch.h"
#include "Undocumented.h"
#include "Tools.h"
#include "EnumNames.h"
#include "MFTools.h"
#include "FrameGenerator.h"

#pragma comment(lib, "winhttp.lib")

HRESULT FrameGenerator::EnsureRenderTarget(UINT width, UINT height)
{
	if (!HasD3DManager())
	{
		// create a D2D1 render target from WIC bitmap
		wil::com_ptr_nothrow<ID2D1Factory> d2d1Factory;
		RETURN_IF_FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_MULTI_THREADED, IID_PPV_ARGS(&d2d1Factory)));

		if (!_wicFactory)
		{
			RETURN_IF_FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&_wicFactory)));
		}

		RETURN_IF_FAILED(_wicFactory->CreateBitmap(width, height, GUID_WICPixelFormat32bppPBGRA, WICBitmapCacheOnDemand, &_bitmap));

		D2D1_RENDER_TARGET_PROPERTIES props{};
		props.pixelFormat.format = DXGI_FORMAT_B8G8R8A8_UNORM;
		props.pixelFormat.alphaMode = D2D1_ALPHA_MODE_PREMULTIPLIED;
		RETURN_IF_FAILED(d2d1Factory->CreateWicBitmapRenderTarget(_bitmap.get(), props, &_renderTarget));

		RETURN_IF_FAILED(CreateRenderTargetResources(width, height));
	}

	StartFrameReader();
	_frame = 0;
	return S_OK;
}

const bool FrameGenerator::HasD3DManager() const
{
	return _texture != nullptr;
}

HRESULT FrameGenerator::SetD3DManager(IUnknown* manager, UINT width, UINT height)
{
	RETURN_HR_IF_NULL(E_POINTER, manager);
	RETURN_HR_IF(E_INVALIDARG, !width || !height);

	RETURN_IF_FAILED(manager->QueryInterface(&_dxgiManager));
	RETURN_IF_FAILED(_dxgiManager->OpenDeviceHandle(&_deviceHandle));

	wil::com_ptr_nothrow<ID3D11Device> device;
	RETURN_IF_FAILED(_dxgiManager->GetVideoService(_deviceHandle, IID_PPV_ARGS(&device)));
	device->GetImmediateContext(_deviceContext.put());

	// create a texture/surface to write
	CD3D11_TEXTURE2D_DESC desc
	(
		DXGI_FORMAT_B8G8R8A8_UNORM,
		width,
		height,
		1,
		1,
		D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET
	);
	RETURN_IF_FAILED(device->CreateTexture2D(&desc, nullptr, &_texture));
	wil::com_ptr_nothrow<IDXGISurface> surface;
	RETURN_IF_FAILED(_texture.copy_to(&surface));

	// create a D2D1 render target from 2D GPU surface
	wil::com_ptr_nothrow<ID2D1Factory> d2d1Factory;
	RETURN_IF_FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_MULTI_THREADED, IID_PPV_ARGS(&d2d1Factory)));

	auto props = D2D1::RenderTargetProperties
	(
		D2D1_RENDER_TARGET_TYPE_DEFAULT,
		D2D1::PixelFormat(DXGI_FORMAT_UNKNOWN, D2D1_ALPHA_MODE_PREMULTIPLIED)
	);
	RETURN_IF_FAILED(d2d1Factory->CreateDxgiSurfaceRenderTarget(surface.get(), props, &_renderTarget));

	RETURN_IF_FAILED(CreateRenderTargetResources(width, height));

	// create GPU RGB => NV12 converter
	RETURN_IF_FAILED(CoCreateInstance(CLSID_VideoProcessorMFT, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&_converter)));

	wil::com_ptr_nothrow<IMFAttributes> atts;
	RETURN_IF_FAILED(_converter->GetAttributes(&atts));
	TraceMFAttributes(atts.get(), L"VideoProcessorMFT");

	MFT_OUTPUT_STREAM_INFO info{};
	RETURN_IF_FAILED(_converter->GetOutputStreamInfo(0, &info));
	WINTRACE(L"FrameGenerator::SetD3DManager CLSID_VideoProcessorMFT flags:0x%08X size:%u alignment:%u", info.dwFlags, info.cbSize, info.cbAlignment);

	wil::com_ptr_nothrow<IMFMediaType> inputType;
	RETURN_IF_FAILED(MFCreateMediaType(&inputType));
	inputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
	inputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
	MFSetAttributeSize(inputType.get(), MF_MT_FRAME_SIZE, width, height);
	RETURN_IF_FAILED(_converter->SetInputType(0, inputType.get(), 0));

	wil::com_ptr_nothrow<IMFMediaType> outputType;
	RETURN_IF_FAILED(MFCreateMediaType(&outputType));
	outputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
	outputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
	MFSetAttributeSize(outputType.get(), MF_MT_FRAME_SIZE, width, height);
	RETURN_IF_FAILED(_converter->SetOutputType(0, outputType.get(), 0));

	// make sure the video processor works on GPU
	RETURN_IF_FAILED(_converter->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, (ULONG_PTR)manager));
	return S_OK;
}

// common to CPU & GPU
HRESULT FrameGenerator::CreateRenderTargetResources(UINT width, UINT height)
{
	assert(_renderTarget);
	_width = width;
	_height = height;
	return S_OK;
}

void FrameGenerator::StartFrameReader()
{
	if (_frameReader.joinable())
		return;

	_stopFrameReader = false;
	_frameReader = std::thread(&FrameGenerator::ReadCameraFrames, this);
}

void FrameGenerator::StopFrameReader()
{
	if (!_frameReader.joinable())
		return;

	_stopFrameReader = true;
	_frameReader.join();
}

void FrameGenerator::ResetFrameStream()
{
	if (_frameStreamRequest)
	{
		WinHttpCloseHandle(_frameStreamRequest);
		_frameStreamRequest = nullptr;
	}
}

HRESULT FrameGenerator::FetchCameraFrame(std::vector<BYTE>& jpeg)
{
	if (!_httpSession)
	{
		_httpSession = WinHttpOpen(L"PocketLens Virtual Camera/1.0", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
		if (!_httpSession)
			return S_FALSE;
		WinHttpSetTimeouts(_httpSession, 500, 500, 500, 1000);
	}
	if (!_httpConnect)
		_httpConnect = WinHttpConnect(_httpSession, L"127.0.0.1", 8787, 0);
	if (!_httpConnect)
		return S_FALSE;

	if (!_frameStreamRequest)
	{
		_frameStreamRequest = WinHttpOpenRequest(_httpConnect, L"GET", L"/frames.raw", nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
		if (!_frameStreamRequest)
			return S_FALSE;

		if (!WinHttpSendRequest(_frameStreamRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
			!WinHttpReceiveResponse(_frameStreamRequest, nullptr))
		{
			ResetFrameStream();
			return S_FALSE;
		}

		DWORD status = 0;
		DWORD statusSize = sizeof(status);
		if (!WinHttpQueryHeaders(_frameStreamRequest, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize, WINHTTP_NO_HEADER_INDEX) ||
			status != 200)
		{
			ResetFrameStream();
			return S_FALSE;
		}
	}

	auto readExact = [this](BYTE* destination, DWORD length)
	{
		DWORD totalRead = 0;
		while (totalRead < length && !_stopFrameReader)
		{
			DWORD bytesRead = 0;
			if (!WinHttpReadData(_frameStreamRequest, destination + totalRead, length - totalRead, &bytesRead) || !bytesRead)
				return false;
			totalRead += bytesRead;
		}
		return totalRead == length;
	};

	BYTE lengthBytes[4];
	if (!readExact(lengthBytes, sizeof(lengthBytes)))
	{
		ResetFrameStream();
		return S_FALSE;
	}

	const DWORD frameLength =
		(DWORD(lengthBytes[0]) << 24) |
		(DWORD(lengthBytes[1]) << 16) |
		(DWORD(lengthBytes[2]) << 8) |
		DWORD(lengthBytes[3]);
	if (frameLength < 4 || frameLength > 32 * 1024 * 1024)
	{
		ResetFrameStream();
		return HRESULT_FROM_WIN32(ERROR_INVALID_DATA);
	}

	try
	{
		jpeg.resize(frameLength);
	}
	catch (const std::bad_alloc&)
	{
		ResetFrameStream();
		return E_OUTOFMEMORY;
	}
	if (!readExact(jpeg.data(), frameLength))
	{
		jpeg.clear();
		ResetFrameStream();
		return S_FALSE;
	}

	return S_OK;
}

void FrameGenerator::ReadCameraFrames()
{
	while (!_stopFrameReader)
	{
		std::vector<BYTE> jpeg;
		const auto result = FetchCameraFrame(jpeg);
		if (result == S_OK)
		{
			std::lock_guard<std::mutex> lock(_frameMutex);
			_latestJpeg = std::move(jpeg);
			_lastFrameAt = std::chrono::steady_clock::now();
			_jpegSequence++;
			continue;
		}
		if (FAILED(result) && result != E_OUTOFMEMORY)
		{
			WINTRACE(L"PocketLens frame stream failed: 0x%08X", result);
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(100));
	}

	ResetFrameStream();
}

HRESULT FrameGenerator::DrawCameraFrame()
{
	std::vector<BYTE> jpeg;
	uint64_t sequence = 0;
	bool frameExpired = false;
	{
		std::lock_guard<std::mutex> lock(_frameMutex);
		if (_jpegSequence != _renderedJpegSequence)
		{
			jpeg = _latestJpeg;
			sequence = _jpegSequence;
		}
		frameExpired = !_latestJpeg.empty() &&
			std::chrono::steady_clock::now() - _lastFrameAt > std::chrono::seconds(2);
	}

	if (sequence != _renderedJpegSequence)
	{
		if (!jpeg.empty())
		{
			if (!_wicFactory)
				RETURN_IF_FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&_wicFactory)));

			wil::com_ptr_nothrow<IWICStream> stream;
			RETURN_IF_FAILED(_wicFactory->CreateStream(&stream));
			RETURN_IF_FAILED(stream->InitializeFromMemory(jpeg.data(), (DWORD)jpeg.size()));

			wil::com_ptr_nothrow<IWICBitmapDecoder> decoder;
			RETURN_IF_FAILED(_wicFactory->CreateDecoderFromStream(stream.get(), nullptr, WICDecodeMetadataCacheOnLoad, &decoder));

			wil::com_ptr_nothrow<IWICBitmapFrameDecode> frame;
			RETURN_IF_FAILED(decoder->GetFrame(0, &frame));

			UINT rotation = 0;
			wil::com_ptr_nothrow<IWICMetadataQueryReader> metadata;
			if (SUCCEEDED(frame->GetMetadataQueryReader(&metadata)))
			{
				PROPVARIANT orientation;
				PropVariantInit(&orientation);
				if (SUCCEEDED(metadata->GetMetadataByName(L"/app1/ifd/{ushort=274}", &orientation)))
				{
					ULONG orientationValue = 1;
					switch (orientation.vt)
					{
					case VT_UI1: orientationValue = orientation.bVal; break;
					case VT_UI2: orientationValue = orientation.uiVal; break;
					case VT_UI4: orientationValue = orientation.ulVal; break;
					case VT_I2: if (orientation.iVal > 0) orientationValue = orientation.iVal; break;
					case VT_I4: if (orientation.lVal > 0) orientationValue = orientation.lVal; break;
					default: break;
					}
					switch (orientationValue)
					{
					case 1: rotation = 180; break;
					case 3: rotation = 0; break;
					case 6: rotation = 90; break;
					case 8: rotation = 270; break;
					default: break;
					}
				}
				PropVariantClear(&orientation);
			}
			_cameraRotation = rotation;

			wil::com_ptr_nothrow<IWICFormatConverter> converter;
			RETURN_IF_FAILED(_wicFactory->CreateFormatConverter(&converter));
			RETURN_IF_FAILED(converter->Initialize(frame.get(), GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0, WICBitmapPaletteTypeCustom));
			RETURN_IF_FAILED(_renderTarget->CreateBitmapFromWicBitmap(converter.get(), nullptr, &_cameraBitmap));
		}
		_renderedJpegSequence = sequence;
	}
	if (frameExpired)
		_cameraBitmap.reset();

	_renderTarget->BeginDraw();
	_renderTarget->Clear(D2D1::ColorF(D2D1::ColorF::Black));
	if (_cameraBitmap)
	{
		const auto sourceSize = _cameraBitmap->GetPixelSize();
		const bool quarterTurn = _cameraRotation == 90 || _cameraRotation == 270;
		const auto orientedWidth = quarterTurn ? sourceSize.height : sourceSize.width;
		const auto orientedHeight = quarterTurn ? sourceSize.width : sourceSize.height;
		const auto scaleX = (FLOAT)_width / orientedWidth;
		const auto scaleY = (FLOAT)_height / orientedHeight;
		const auto scale = scaleX < scaleY ? scaleX : scaleY;
		const auto drawWidth = sourceSize.width * scale;
		const auto drawHeight = sourceSize.height * scale;
		const auto centerX = _width / 2.0f;
		const auto centerY = _height / 2.0f;
		const auto left = centerX - drawWidth / 2.0f;
		const auto top = centerY - drawHeight / 2.0f;
		if (_cameraRotation != 0)
			_renderTarget->SetTransform(D2D1::Matrix3x2F::Rotation((FLOAT)_cameraRotation, D2D1::Point2F(centerX, centerY)));
		_renderTarget->DrawBitmap(_cameraBitmap.get(), D2D1::RectF(left, top, left + drawWidth, top + drawHeight));
		if (_cameraRotation != 0)
			_renderTarget->SetTransform(D2D1::Matrix3x2F::Identity());
	}
	return _renderTarget->EndDraw();
}

HRESULT FrameGenerator::Generate(IMFSample* sample, REFGUID format, IMFSample** outSample)
{
	RETURN_HR_IF_NULL(E_POINTER, sample);
	RETURN_HR_IF_NULL(E_POINTER, outSample);
	*outSample = nullptr;

	RETURN_IF_FAILED(DrawCameraFrame());

	// build a sample using either D3D/DXGI (GPU) or WIC (CPU)
	wil::com_ptr_nothrow<IMFMediaBuffer> mediaBuffer;
	if (HasD3DManager())
	{
		if (format == MFVideoFormat_NV12)
		{
			// Feed the converter from the shared render texture; it creates an output sample per request.
			RETURN_IF_FAILED(sample->RemoveAllBuffers());
			RETURN_IF_FAILED(MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), _texture.get(), 0, 0, &mediaBuffer));
			RETURN_IF_FAILED(sample->AddBuffer(mediaBuffer.get()));
			assert(_converter);
			RETURN_IF_FAILED(_converter->ProcessInput(0, sample, 0));

			MFT_OUTPUT_DATA_BUFFER buffer = {};
			DWORD status = 0;
			RETURN_IF_FAILED(_converter->ProcessOutput(0, 1, &buffer, &status));
			*outSample = buffer.pSample;
		}
		else
		{
			// The allocator owns each output surface. Do not expose the shared render
			// texture directly: consumers may retain samples while the next frame draws.
			RETURN_IF_FAILED(sample->GetBufferByIndex(0, &mediaBuffer));
			wil::com_ptr_nothrow<IMFDXGIBuffer> dxgiBuffer;
			RETURN_IF_FAILED(mediaBuffer->QueryInterface(IID_PPV_ARGS(&dxgiBuffer)));
			wil::com_ptr_nothrow<ID3D11Texture2D> outputTexture;
			RETURN_IF_FAILED(dxgiBuffer->GetResource(IID_PPV_ARGS(&outputTexture)));
			_deviceContext->CopyResource(outputTexture.get(), _texture.get());
			sample->AddRef();
			*outSample = sample;
		}

		_frame++;
		return S_OK;
	}

	RETURN_IF_FAILED(sample->GetBufferByIndex(0, &mediaBuffer));
	wil::com_ptr_nothrow<IMF2DBuffer2> buffer2D;
	BYTE* scanline;
	LONG pitch;
	BYTE* start;
	DWORD length;
	RETURN_IF_FAILED(mediaBuffer->QueryInterface(IID_PPV_ARGS(&buffer2D)));
	RETURN_IF_FAILED(buffer2D->Lock2DSize(MF2DBuffer_LockFlags_Write, &scanline, &pitch, &start, &length));

	wil::com_ptr_nothrow<IWICBitmapLock> lock;
	auto hr = _bitmap->Lock(nullptr, WICBitmapLockRead, &lock);
	// now we're using regular COM macros because we want to be sure to unlock (or we could use try/catch)
	if (SUCCEEDED(hr))
	{
		UINT w, h;
		hr = lock->GetSize(&w, &h);
		if (SUCCEEDED(hr))
		{
			UINT wicStride;
			hr = lock->GetStride(&wicStride);
			if (SUCCEEDED(hr))
			{
				UINT wicSize;
				WICInProcPointer wicPointer;
				hr = lock->GetDataPointer(&wicSize, &wicPointer);
				if (SUCCEEDED(hr))
				{
					WINTRACE(L"WIC stride:%u WIC size:%u MF pitch:%u MF length:%u frame:%u format:%s", wicStride, wicSize, pitch, length, _frame, GUID_ToStringW(format).c_str());
					if (format == MFVideoFormat_NV12)
					{
						// note we could use MF's converter too
						hr = RGB32ToNV12(wicPointer, wicSize, wicStride, w, h, scanline, length, pitch);
					}
					else
					{
						hr = (wicSize != length || wicStride != pitch) ? E_FAIL : S_OK;
						if (SUCCEEDED(hr))
						{
							if (assert_true(wicPointer)) // WIC annotation is currently wrong on GetDataPointer wicPointer arg
							{
								CopyMemory(scanline, wicPointer, length);
							}
						}
					}

					if (SUCCEEDED(hr))
					{
						_frame++;
						sample->AddRef();
						*outSample = sample;
					}
				}
			}
		}
		lock.reset();
	}

	buffer2D->Unlock2D();
	return hr;
}
